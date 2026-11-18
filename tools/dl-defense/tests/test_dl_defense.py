"""python -m unittest discover -s tests  (tools/dl-defense 에서 실행). 인프라 없이 몇 초면 끝난다."""
import json
import sys
import tempfile
import unittest
from pathlib import Path

import numpy as np
import torch

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

import score as score_cli  # noqa: E402
from jejari_dl import botcsv, group, metrics, model as model_mod, realdata, schema, sim, training  # noqa: E402


class SchemaTest(unittest.TestCase):
    def test_to_batch_shapes_and_padding(self):
        s = sim.make_dataset(5, 4, seed=1)
        b = schema.to_batch(s)
        self.assertEqual(b.types.shape, (9, schema.MAX_LEN))
        self.assertTrue((b.dt[:, 0] == 0).all())
        for i, subj in enumerate(s):
            self.assertEqual(b.length[i], len(subj.types))
            self.assertTrue((b.dt[i, b.length[i]:] == 0).all())

    def test_empty_and_mismatched_are_rejected(self):
        with self.assertRaises(ValueError):
            schema.to_batch([schema.Subject([], [], [])])
        with self.assertRaises(ValueError):
            schema.Subject([1], [0.0, 1.0], [0])

    def test_status_class(self):
        self.assertEqual(schema.status_class(200), schema.STATUS_OK)
        self.assertEqual(schema.status_class(409), schema.STATUS_CONFLICT)
        self.assertEqual(schema.status_class(500), schema.STATUS_OTHER)


class SimTest(unittest.TestCase):
    def test_same_seed_same_data(self):
        a = sim.make_dataset(20, 12, seed=7)
        b = sim.make_dataset(20, 12, seed=7)
        self.assertEqual([x.types for x in a], [x.types for x in b])
        self.assertNotEqual([x.types for x in a], [x.types for x in sim.make_dataset(20, 12, seed=8)])

    def test_every_family_is_valid(self):
        rng = np.random.default_rng(0)
        for fam in sim.BOT_FAMILIES:
            for _ in range(30):
                s = sim.gen_bot(rng, fam)
                self.assertEqual(s.label, 1)
                self.assertTrue(1 <= len(s.types) <= schema.MAX_LEN)
                self.assertTrue(all(d >= 0 and np.isfinite(d) for d in s.dt_ms))
        for _ in range(100):
            h = sim.gen_human(rng)
            self.assertEqual(h.label, 0)
            self.assertTrue(1 <= len(h.types) <= schema.MAX_LEN)

    def test_families_have_the_intended_property(self):
        rng = np.random.default_rng(1)
        fixed = [sim.gen_bot(rng, "fixed_interval") for _ in range(50)]
        cv = [np.std(s.dt_ms[1:]) / np.mean(s.dt_ms[1:]) for s in fixed]
        self.assertLess(np.median(cv), 0.1)                       # 일정한 간격
        multi = sim.gen_bot(rng, "multi_account")
        self.assertGreaterEqual(multi.sync, 2)                    # 공유 신호


class BotCsvTest(unittest.TestCase):
    HEADER = "session_id,email,label,bot_type,step_idx,event,ts_ms,dt_ms,seat,n_seats,ip_hash,ua_hash\n"

    def csv_text(self):
        rows = [("s1", "login", 1000), ("s1", "search", 1100), ("s1", "reserve_click", 1150),
                ("s1", "queue_join", 1300), ("s1", "queue_pass", 2300), ("s1", "seat_select", 2400),
                ("s1", "payment", 2500)]
        # email·ip·ua에 이상한 값을 넣어도 모델 입력이 같아야 한다
        return self.HEADER + "".join(f"{sid},evil@loadtest.example.com,1,naive,0,{e},{t},0,,1,ipX,uaX\n"
                                     for sid, e, t in rows)

    def test_mapping_drops_unmapped_and_recomputes_gaps(self):
        with tempfile.TemporaryDirectory() as d:
            f = Path(d) / "a.csv"
            f.write_text(self.csv_text(), encoding="utf-8")
            (s,) = botcsv.read_csv(f)
        names = [schema.EVENT_TYPES[t] for t in s.types]
        self.assertEqual(names, ["VIEW_TRAIN", "QUEUE_ENTER", "VIEW_SEAT", "SEAT_HOLD", "PAY"])
        self.assertEqual(s.dt_ms, [0.0, 200.0, 1000.0, 100.0, 100.0])   # login·reserve_click을 버려도 시각 차이 유지
        self.assertEqual((s.label, s.family, s.linked, s.sync), (1, "naive", 1, 0))   # ip를 신호로 쓰지 않음

    def test_v2_events_and_human_rows(self):
        rows = [("h1", "login", 0), ("h1", "search", 2000), ("h1", "seat_select", 5000),
                ("h1", "seat_deselect", 6000), ("h1", "seat_select", 8000), ("h1", "payment", 20000)]
        text = self.HEADER + "".join(f"{sid},a@b,0,human_synth,0,{e},{t},0,,1,ip,ua\n" for sid, e, t in rows)
        with tempfile.TemporaryDirectory() as d:
            f = Path(d) / "v2.csv"
            f.write_text(text, encoding="utf-8")
            (s,) = botcsv.read_csv(f)
        self.assertEqual((s.label, s.family), (0, "human_synth"))
        self.assertEqual([schema.EVENT_TYPES[t] for t in s.types], ["VIEW_TRAIN", "SEAT_HOLD", "SEAT_HOLD", "PAY"])
        self.assertEqual(s.dt_ms, [0.0, 3000.0, 3000.0, 12000.0])      # seat_deselect를 버려도 시각 차이 유지

    def test_v3_family_conflict_and_device_context(self):
        head = "session_id,email,label,family,variant,step_idx,event,ts_ms,dt_ms,train,seat,n_seats,ip_hash,ua_hash\n"
        def sess(sid, label, fam, var, ua, t0):
            evs = [("search", 0), ("seat_select", 1000), ("seat_conflict", 1100), ("hold", 600000)]
            return "".join(f"{sid},x,{label},{fam},{var},0,{e},{t0 + t},0,1,,1,ipZ,{ua}\n" for e, t in evs)
        text = head + sess("a", 1, "multi_account", "same_ip", "UA1", 0) + sess("b", 1, "multi_account", "same_ip", "UA1", 5000) \
            + sess("c", 0, "human_synth", "human", "UA2", 0)
        with tempfile.TemporaryDirectory() as d:
            f = Path(d) / "v3.csv"
            f.write_text(text, encoding="utf-8")
            subs = {s.family: s for s in botcsv.read_csv(f, device_context=True)}
            off = {s.family: s for s in botcsv.read_csv(f)}
        a = subs["multi_account/same_ip"]
        self.assertEqual(a.status, [schema.STATUS_OK, schema.STATUS_OK, schema.STATUS_CONFLICT])   # seat_conflict → 409, hold는 버림
        self.assertEqual((a.linked, a.sync), (2, 2))                                              # 같은 ua, 60초 안 동시 선점
        self.assertEqual((subs["human_synth"].linked, subs["human_synth"].sync), (1, 0))
        self.assertEqual((off["multi_account/same_ip"].linked, off["multi_account/same_ip"].sync), (1, 0))   # 옵션이 꺼져 있으면 안 씀

    def test_human_sessions_are_valid_and_label_zero(self):
        hs = botcsv.make_humans(200, seed=3)
        self.assertTrue(all(h.label == 0 and h.family == "csv_human" for h in hs))
        self.assertTrue(all(1 <= len(h.types) <= schema.MAX_LEN and min(h.dt_ms[1:] or [1]) > 0 for h in hs))
        self.assertEqual([h.types for h in hs[:5]], [h.types for h in botcsv.make_humans(5, seed=3)])


class GroupTest(unittest.TestCase):
    def mk(self, dts, key=""):
        types = [0, 0, 2, 1, 3, 3, 3, 4, 4][:len(dts) + 1]
        return schema.Subject(types, [0.0] + list(dts), [0] * (len(dts) + 1), 1, 0, 0, "x", key)

    def test_near_duplicate_timing_is_counted_and_random_is_not(self):
        base = [2000, 3000, 1000, 1500, 2500, 900, 4000, 3000]
        rng = np.random.default_rng(0)
        near = [self.mk([d * rng.uniform(0.9, 1.1) for d in base]) for _ in range(4)]
        far = [self.mk(list(rng.lognormal(7.5, 0.8, 8))) for _ in range(30)]
        short = schema.Subject([0, 1], [0.0, 500.0], [0, 0])
        counts = group.dup_counts(near + far + [short])
        self.assertTrue(all(c == 3 for c in counts[:4]))            # 서로 다른 3개
        self.assertLessEqual(int(counts[4:].max()), 1)              # 독립 간격은 거의 안 겹침
        self.assertEqual(counts[-1], 0)                             # 너무 짧은 세션은 비교 안 함

    def test_same_key_windows_do_not_count_each_other(self):
        d = [2000, 3000, 1000, 1500, 2500, 900, 4000, 3000]
        a, b = self.mk(d, "m:1"), self.mk(d, "m:1")
        c = self.mk(d, "m:2")
        self.assertEqual(list(group.dup_counts([a, b, c])), [1, 1, 2])


class TierTest(unittest.TestCase):
    def test_union_thresholds_respect_target_and_spec_share(self):
        rng = np.random.default_rng(0)
        main, spec = rng.random(5000), rng.random(5000)
        thr = training.fit_union_thresholds([main, spec], target_fpr=0.02, spec_fpr=0.005)
        hit = (main >= thr[0]) | (spec >= thr[1])
        self.assertLessEqual(hit.mean(), 0.02 + 1e-9)
        self.assertLessEqual((spec >= thr[1]).mean(), 0.005 + 1e-3)       # 전용 모델은 자기 몫만
        off = training.fit_union_thresholds([main, spec], 0.02, 0.0)       # 전용 끄기
        self.assertTrue(np.isinf(off[1]))
        self.assertLessEqual((main >= off[0]).mean(), 0.02 + 1e-9)

    def test_tiers_are_nested_and_ordered(self):
        main = np.array([0.1, 0.5, 0.9, 0.2])
        spec = np.array([0.0, 0.0, 0.0, 0.8])
        t = training.assign_tier([main, spec], hard=[0.8, 0.95], soft=[0.4, 0.5])
        self.assertEqual(list(t), [0, 1, 2, 1])                            # 0.5는 약함, 0.9는 강함, spec 0.8은 약함
        # soft가 hard보다 엄격하게 잘못 주어져도 약한 단계가 강한 단계를 포함하도록 보정된다
        t2 = training.assign_tier([main], hard=[0.8], soft=[0.95])
        self.assertEqual(list(t2), [0, 0, 2, 0])
        with self.assertRaises(ValueError):
            training.assign_tier([main], hard=[0.8, 0.9], soft=[0.4])

    def test_score_union_cli_gives_tiers_and_shared_device(self):
        cfg = training.TrainConfig(epochs=1, hidden=16)
        m = training.fit(sim.make_dataset(60, 40, seed=31), sim.make_dataset(20, 14, seed=32), cfg, log=None)
        base = "2026-11-23T10:00:{:02d}+09:00"
        lines = [json.dumps({"eventType": "SEAT_HOLD", "ts": base.format(i), "memberId": mid, "deviceHash": "dev", "responseStatus": 200})
                 for i, mid in ((1, 1), (2, 2), (3, 3))]
        with tempfile.TemporaryDirectory() as d:
            mp = Path(d) / "m.pt"
            training.save(m, mp, 0.5, cfg)
            f = Path(d) / "e.jsonl"
            f.write_text("\n".join(lines), encoding="utf-8")
            spec = [{"name": "main", "path": str(mp), "thr": 0.99, "soft_thr": 0.01}]
            rows = score_cli.score_file_union(spec, f)
        self.assertEqual(len(rows), 3)
        self.assertTrue(all(r["tier"] == 2 and "shared_device" in r["reasons"] for r in rows))   # 같은 기기 3계정 동시 선점
        self.assertTrue(all(r["flag"] for r in rows))


class ModelTest(unittest.TestCase):
    def test_forward_and_padding_does_not_change_score(self):
        torch.manual_seed(0)
        m = model_mod.BotDetector(16).eval()
        s = sim.make_dataset(3, 2, seed=3)
        b = schema.to_batch(s)
        full = training.predict(m, b)
        # 같은 대상을 더 긴 패딩으로 만들어도 점수가 같아야 한다 (마스킹)
        b2 = schema.to_batch(s, max_len=schema.MAX_LEN + 8)
        np.testing.assert_allclose(full, training.predict(m, b2), atol=1e-5)
        self.assertTrue(((full >= 0) & (full <= 1)).all())

    def test_train_save_load_roundtrip(self):
        cfg = training.TrainConfig(epochs=2, hidden=16, seed=0)
        tr = sim.make_dataset(120, 90, seed=11)
        va = sim.make_dataset(40, 30, seed=12)
        m = training.fit(tr, va, cfg, log=None)
        p = training.predict(m, va)
        thr = training.pick_threshold(p[[s.label == 0 for s in va]], 0.05)
        with tempfile.TemporaryDirectory() as d:
            path = Path(d) / "m.pt"
            training.save(m, path, thr, cfg)
            m2, thr2 = training.load(path)
            self.assertEqual(thr, thr2)
            np.testing.assert_allclose(p, training.predict(m2, va), atol=1e-6)


class MetricsTest(unittest.TestCase):
    def test_auc(self):
        self.assertEqual(metrics.auc(np.array([.9, .8, .2, .1]), np.array([1, 1, 0, 0])), 1.0)
        self.assertEqual(metrics.auc(np.array([.1, .2, .8, .9]), np.array([1, 1, 0, 0])), 0.0)
        self.assertAlmostEqual(metrics.auc(np.array([.5, .5, .5, .5]), np.array([1, 1, 0, 0])), 0.5)

    def test_threshold_respects_fpr(self):
        humans = np.linspace(0, 1, 1000)
        thr = training.pick_threshold(humans, 0.05)
        self.assertLessEqual((humans >= thr).mean(), 0.0511)

    def test_rule_baseline_flags_machine_gun_not_slow_human(self):
        rng = np.random.default_rng(2)
        sniper = sim.gen_bot(rng, "sniper")
        self.assertTrue(metrics.rule_baseline_flag(sniper))
        careful = [sim.gen_human(rng, "careful") for _ in range(100)]
        self.assertLess(np.mean([metrics.rule_baseline_flag(h) for h in careful]), 0.05)


def _line(member, device, etype, t, status=200, **extra):
    d = {"eventType": etype, "ts": t, "memberId": member, "deviceHash": device, "ipHash": "ip-shared",
         "responseStatus": status}
    d.update(extra)
    return json.dumps(d)


class RealDataTest(unittest.TestCase):
    def lines(self):
        base = "2026-11-23T10:00:{:02d}+09:00"
        out = [_line(1, "devA", "QUEUE_ENTER", base.format(0)),
               _line(1, "devA", "SEAT_HOLD", base.format(2), 409),
               _line(2, "devA", "SEAT_HOLD", base.format(3)),            # 같은 기기, 다른 회원, 60초 안
               _line(3, "devB", "SEAT_HOLD", base.format(4)),
               _line(1, "devA", "SOMETHING_NEW", base.format(5)),         # 모르는 종류는 건너뜀
               _line(4, "devC", "VIEW_TRAIN", base.format(6), email="x@loadtest.example.com", label="bot")]
        return out

    def test_group_gaps_and_shared_device(self):
        subs = {s.key: s for s in realdata.build_subjects(realdata.read_events(self.lines()))}
        self.assertEqual(set(subs), {"m:1", "m:2", "m:3", "m:4"})
        s1 = subs["m:1"]
        self.assertEqual(len(s1.types), 2)
        self.assertAlmostEqual(s1.dt_ms[1], 2000.0)
        self.assertEqual(s1.status, [schema.STATUS_OK, schema.STATUS_CONFLICT])
        self.assertEqual((s1.linked, s1.sync), (2, 2))            # devA에 회원 1·2가 60초 안에 선점
        self.assertEqual((subs["m:3"].linked, subs["m:3"].sync), (1, 1))   # 같은 ipHash라도 기기가 다르면 묶지 않음
        self.assertIsNone(s1.label)

    def test_long_history_is_windowed(self):
        lines = [_line(9, "d", "VIEW_TRAIN", "2026-11-23T10:%02d:%02d+09:00" % (i // 60, i % 60)) for i in range(150)]
        subs = realdata.build_subjects(realdata.read_events(lines))
        self.assertGreater(len(subs), 2)
        self.assertTrue(all(len(s.types) <= schema.MAX_LEN for s in subs))
        self.assertEqual(subs[-1].types and len(subs[-1].types), schema.MAX_LEN)   # 끝 창은 마지막 이벤트까지 덮는다

    def test_score_cli_end_to_end(self):
        cfg = training.TrainConfig(epochs=1, hidden=16)
        m = training.fit(sim.make_dataset(60, 40, seed=21), sim.make_dataset(20, 14, seed=22), cfg, log=None)
        with tempfile.TemporaryDirectory() as d:
            f = Path(d) / "e.jsonl"
            f.write_text("\n".join(self.lines()), encoding="utf-8")
            rows = score_cli.score_file(m, 0.5, f)
        by = {r["key"]: r for r in rows}
        self.assertEqual(len(rows), 4)
        self.assertIn("shared_device", by["m:1"]["reasons"])      # 모델과 별개로 공유 기기 표시
        self.assertTrue(by["m:1"]["flag"])
        self.assertTrue(all(0 <= r["score"] <= 100 for r in rows))


if __name__ == "__main__":
    unittest.main()
