package ksh.tryptoengine.wal;

import ksh.tryptoengine.engine.OrderDetail;

import java.util.List;

public record Snapshot(
    long lastSeq,
    List<PairSnapshot> pairs
) {
    public record PairSnapshot(Long exchangeCoinId, List<OrderDetail> orders) {}
}
