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
from jejari_dl import metrics, model as model_mod, realdata, schema, sim, training  # noqa: E402


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
