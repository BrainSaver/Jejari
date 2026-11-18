// DATA-04 Hotel Booking 분석 (MongoDB 집계). 실행: bash tools/ext-hotel/analyze.sh
// 우리 규칙·가상 데이터에 근거가 필요한 질문만 본다. 결과는 JSON 한 덩어리로 출력.
//
//   Q1 취소율 (호텔별)                       → 정상 회원 취소 비율의 바닥값
//   Q2 도착 며칠 전에 취소하나               → LAST_MINUTE_CANCEL, 가상 데이터 minutes_before_departure
//   Q3 예약하고 며칠 뒤 취소하나             → CANCEL_AFTER_PAY (구매 직후 취소)
//   Q4 일찍 예약할수록 더 취소하나           → 예매 시점과 취소의 관계
//   Q5 과거에 취소한 사람은 또 취소하나      → 반복 취소 가중치 (HIGH_CANCEL_RATIO 등)
//   Q6 재방문·정상 이용 이력이 있으면?       → 신뢰 이력
//   Q7 환불 조건별 취소율                    → 취소 수수료 정책
//   Q8 출장(Corporate) 고객                  → 조영준 님 "출장자 직전 취소" 범위 근거
const DS = 'hotel_booking@v1';
const c = db.ext_hotel_booking;
const MONTHS = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October',
  'November', 'December'];
const base = { $match: { dataset_id: DS } };
const DAY = 86400000;
// 날짜 계산: 도착일, 상태 변경일(취소일), 예약일 = 도착일 − lead_time
const dates = {
  $addFields: {
    arrival: {
      $dateFromParts: {
        year: '$arrival_date_year', month: { $add: [{ $indexOfArray: [MONTHS, '$arrival_date_month'] }, 1] },
        day: '$arrival_date_day_of_month',
      },
    },
    statusAt: { $dateFromString: { dateString: { $toString: '$reservation_status_date' } } },
  },
};
const derived = {
  $addFields: {
    bookedAt: { $subtract: ['$arrival', { $multiply: ['$lead_time', DAY] }] },
    daysBeforeArrival: { $round: [{ $divide: [{ $subtract: ['$arrival', '$statusAt'] }, DAY] }, 0] },
  },
};
const derived2 = {
  $addFields: { daysAfterBooking: { $round: [{ $divide: [{ $subtract: ['$statusAt', '$bookedAt'] }, DAY] }, 0] } },
};
const rate = (match) => ({
  n: { $sum: 1 }, canceled: { $sum: '$is_canceled' },
});
function rows(pipeline) {
  return c.aggregate([base, ...pipeline], { allowDiskUse: true }).toArray().map((r) => {
    if (r.n != null && r.canceled != null) r.cancelRate = Math.round((r.canceled / r.n) * 1000) / 10;
    return r;
  });
}
function bucket(field, bounds) {
  return { $bucket: { groupBy: field, boundaries: bounds, default: 'other', output: rate() } };
}
// 취소 건만 (reservation_status = Canceled, 노쇼 제외)
const canceledOnly = { $match: { reservation_status: 'Canceled' } };
function share(pipeline) {
  const r = rows(pipeline);
  const total = r.reduce((s, x) => s + x.count, 0);
  return r.map((x) => ({ ...x, pct: Math.round((x.count / total) * 1000) / 10 }));
}

const out = {
  dataset: DS,
  total: c.countDocuments({ dataset_id: DS }),
  Q1_cancelRateByHotel: rows([{ $group: { _id: '$hotel', ...rate() } }, { $sort: { _id: 1 } }]),
  Q2_daysBeforeArrivalWhenCanceled: share([canceledOnly, dates, derived,
    { $bucket: { groupBy: '$daysBeforeArrival', boundaries: [0, 1, 2, 4, 8, 31, 91, 100000], default: 'other',
      output: { count: { $sum: 1 } } } }]),
  Q3_daysAfterBookingWhenCanceled: share([canceledOnly, dates, derived, derived2,
    { $bucket: { groupBy: '$daysAfterBooking', boundaries: [0, 1, 2, 8, 31, 91, 100000], default: 'other',
      output: { count: { $sum: 1 } } } }]),
  Q4_cancelRateByLeadTime: rows([bucket('$lead_time', [0, 1, 8, 31, 91, 181, 366, 100000])]),
  Q5_cancelRateByPreviousCancellations: rows([
    { $group: { _id: { $min: ['$previous_cancellations', 3] }, ...rate() } }, { $sort: { _id: 1 } }]),
  // Non Refund 예약은 취소율 99%라는 알려진 이상값(단체·대리점 가예약 추정)이 있어 빼고 다시 본다
  Q5b_previousCancellations_excludingNonRefund: rows([{ $match: { deposit_type: { $ne: 'Non Refund' } } },
    { $group: { _id: { $min: ['$previous_cancellations', 3] }, ...rate() } }, { $sort: { _id: 1 } }]),
  Q6_cancelRateByHistory: rows([
    { $group: {
      _id: { repeatedGuest: '$is_repeated_guest', keptBefore: { $gt: ['$previous_bookings_not_canceled', 0] } },
      ...rate() } }, { $sort: { '_id.repeatedGuest': 1, '_id.keptBefore': 1 } }]),
  Q7_cancelRateByDeposit: rows([{ $group: { _id: '$deposit_type', ...rate() } }, { $sort: { _id: 1 } }]),
  Q8_corporate: {
    cancelRateBySegment: rows([{ $match: { hotel: 'City Hotel' } }, { $group: { _id: '$market_segment', ...rate() } },
      { $sort: { n: -1 } }]),
    leadTimeMedian: c.aggregate([base, { $match: { hotel: 'City Hotel', market_segment: 'Corporate' } },
      { $group: { _id: null, m: { $median: { input: '$lead_time', method: 'approximate' } } } }]).toArray()[0]?.m,
    canceledDaysBeforeArrival: share([{ $match: { hotel: 'City Hotel', market_segment: 'Corporate' } }, canceledOnly,
      dates, derived, { $bucket: { groupBy: '$daysBeforeArrival', boundaries: [0, 1, 2, 4, 8, 31, 100000],
        default: 'other', output: { count: { $sum: 1 } } } }]),
    previousCancellations: share([{ $match: { hotel: 'City Hotel', market_segment: 'Corporate' } },
      { $group: { _id: { $min: ['$previous_cancellations', 3] }, count: { $sum: 1 } } }, { $sort: { _id: 1 } }]),
  },
};
print(JSON.stringify(out, null, 1));
