package ksh.tryptoengine.wal;

import com.fasterxml.jackson.databind.JsonNode;

public record WalRecord(long sequence, String eventType, JsonNode event) {
}
