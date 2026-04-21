package ksh.tryptoengine.holding;

import ksh.tryptoengine.engine.OrderDetail;
import ksh.tryptoengine.engine.Side;
import ksh.tryptoengine.engine.TradingPair;
import ksh.tryptoengine.event.FillCommand;
import ksh.tryptoengine.holding.HoldingIncrementalUpdater.HoldingState;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HoldingIncrementalUpdaterTest {

    private static final long WALLET = 1L;
    private static final long COIN = 10L;

    @Test
    void 첫_BUY는_평단과_수량을_그대로_세팅() {
        HoldingState result = HoldingIncrementalUpdater.applyFills(
            HoldingIncrementalUpdater.EMPTY,
            List.of(buy("100", "5"))
        );

        assertThat(result.avg()).isEqualByComparingTo("100");
        assertThat(result.qty()).isEqualByComparingTo("5");
        assertThat(result.totalBuy()).isEqualByComparingTo("500");
        assertThat(result.adCount()).isZero();
    }

    @Test
    void 더_비싼_가격으로_BUY하면_평단이_상승하고_adCount는_그대로() {
        HoldingState start = new HoldingState(bd("100"), bd("5"), bd("500"), 0);

        HoldingState result = HoldingIncrementalUpdater.applyFills(start, List.of(buy("200", "5")));

        assertThat(result.avg()).isEqualByComparingTo("150");
        assertThat(result.qty()).isEqualByComparingTo("10");
        assertThat(result.totalBuy()).isEqualByComparingTo("1500");
        assertThat(result.adCount()).isZero();
    }

    @Test
    void 더_싼_가격으로_BUY하면_평단이_하락하고_adCount가_1_증가() {
        HoldingState start = new HoldingState(bd("200"), bd("5"), bd("1000"), 0);

        HoldingState result = HoldingIncrementalUpdater.applyFills(start, List.of(buy("100", "5")));

        assertThat(result.avg()).isEqualByComparingTo("150");
        assertThat(result.qty()).isEqualByComparingTo("10");
        assertThat(result.totalBuy()).isEqualByComparingTo("1500");
        assertThat(result.adCount()).isEqualTo(1);
    }

    @Test
    void SELL은_수량만_감소하고_평단은_유지() {
        HoldingState start = new HoldingState(bd("150"), bd("10"), bd("1500"), 0);

        HoldingState result = HoldingIncrementalUpdater.applyFills(start, List.of(sell("500", "3")));

        assertThat(result.avg()).isEqualByComparingTo("150");
        assertThat(result.qty()).isEqualByComparingTo("7");
        assertThat(result.totalBuy()).isEqualByComparingTo("1500");
        assertThat(result.adCount()).isZero();
    }

    @Test
    void 전량_SELL_후_수량은_0이고_평단은_유지() {
        HoldingState start = new HoldingState(bd("150"), bd("10"), bd("1500"), 0);

        HoldingState result = HoldingIncrementalUpdater.applyFills(start, List.of(sell("999", "10")));

        assertThat(result.avg()).isEqualByComparingTo("150");
        assertThat(result.qty()).isEqualByComparingTo("0");
    }

    @Test
    void 배치에서_여러_체결을_순차_적용한_결과는_단건씩_누적한_결과와_같다() {
        List<FillCommand> fills = List.of(
            buy("100", "5"),
            buy("200", "5"),
            sell("1000", "2"),
            buy("50", "3")
        );

        HoldingState batched = HoldingIncrementalUpdater.applyFills(
            HoldingIncrementalUpdater.EMPTY, fills
        );

        HoldingState incremental = HoldingIncrementalUpdater.EMPTY;
        for (FillCommand f : fills) {
            incremental = HoldingIncrementalUpdater.applyFills(incremental, List.of(f));
        }

        assertThat(batched).isEqualTo(incremental);
    }

    @Test
    void 같은_체결_시퀀스에_대해_증분_결과는_원본_전체재계산_결과와_동일하다() {
        List<FillCommand> fills = List.of(
            buy("120.50", "3.5"),
            buy("115.25", "2"),
            sell("300", "1.5"),
            buy("130", "4"),
            buy("110", "1.25"),
            sell("400", "2")
        );

        HoldingState incremental = HoldingIncrementalUpdater.applyFills(
            HoldingIncrementalUpdater.EMPTY, fills
        );
        HoldingState naive = naiveReplay(fills);

        assertThat(incremental.avg()).isEqualByComparingTo(naive.avg());
        assertThat(incremental.qty()).isEqualByComparingTo(naive.qty());
        assertThat(incremental.totalBuy()).isEqualByComparingTo(naive.totalBuy());
        assertThat(incremental.adCount()).isEqualTo(naive.adCount());
    }

    @Test
    void 비어있지_않은_초기_상태에_증분을_적용한_결과도_전체재계산과_동일하다() {
        List<FillCommand> history = List.of(
            buy("100", "10"),
            sell("500", "2"),
            buy("90", "5")
        );
        List<FillCommand> newFills = List.of(
            buy("80", "3"),
            sell("700", "4")
        );

        HoldingState afterHistory = naiveReplay(history);
        HoldingState incremental = HoldingIncrementalUpdater.applyFills(afterHistory, newFills);

        List<FillCommand> all = new ArrayList<>(history);
        all.addAll(newFills);
        HoldingState naive = naiveReplay(all);

        assertThat(incremental.avg()).isEqualByComparingTo(naive.avg());
        assertThat(incremental.qty()).isEqualByComparingTo(naive.qty());
        assertThat(incremental.totalBuy()).isEqualByComparingTo(naive.totalBuy());
        assertThat(incremental.adCount()).isEqualTo(naive.adCount());
    }

    private static HoldingState naiveReplay(List<FillCommand> fills) {
        BigDecimal qty = BigDecimal.ZERO;
        BigDecimal avg = BigDecimal.ZERO;
        BigDecimal totalBuy = BigDecimal.ZERO;
        int adCount = 0;
        for (FillCommand cmd : fills) {
            BigDecimal p = cmd.executedPrice();
            BigDecimal q = cmd.order().quantity();
            if (cmd.order().side() == Side.BUY) {
                BigDecimal newQty = qty.add(q);
                BigDecimal newAvg = qty.signum() == 0
                    ? p
                    : avg.multiply(qty).add(p.multiply(q)).divide(newQty, 8, RoundingMode.HALF_UP);
                if (qty.signum() > 0 && newAvg.compareTo(avg) < 0) {
                    adCount++;
                }
                totalBuy = totalBuy.add(p.multiply(q));
                qty = newQty;
                avg = newAvg;
            } else {
                qty = qty.subtract(q);
            }
        }
        return new HoldingState(avg, qty, totalBuy, adCount);
    }

    private static FillCommand buy(String price, String qty) {
        return fill(Side.BUY, price, qty);
    }

    private static FillCommand sell(String price, String qty) {
        return fill(Side.SELL, price, qty);
    }

    private static FillCommand fill(Side side, String price, String qty) {
        OrderDetail order = new OrderDetail(
            1L, 1L, WALLET,
            side, new TradingPair(100L),
            COIN, 200L,
            bd(price), bd(qty), BigDecimal.ZERO, 200L,
            LocalDateTime.of(2025, 1, 1, 0, 0)
        );
        return new FillCommand(order, bd(price), LocalDateTime.of(2025, 1, 1, 0, 0));
    }

    private static BigDecimal bd(String v) {
        return new BigDecimal(v);
    }
}
