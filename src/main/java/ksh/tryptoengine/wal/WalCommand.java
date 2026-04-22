package ksh.tryptoengine.wal;

import java.util.concurrent.CountDownLatch;

sealed interface WalCommand {
    record Write(WalRecord record) implements WalCommand {}
    record Rotate(CountDownLatch done) implements WalCommand {}
    record Flush(CountDownLatch done) implements WalCommand {}
}
