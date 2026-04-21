package ksh.tryptoengine.wal;

import com.fasterxml.jackson.databind.ObjectMapper;
import ksh.tryptoengine.engine.OrderBookRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
public class SnapshotWriter {

    static final String SNAPSHOT_FILE = "snapshot.json";
    static final String SNAPSHOT_TMP_FILE = "snapshot.json.tmp";

    private final ObjectMapper mapper;
    private final Path walDir;

    public SnapshotWriter(ObjectMapper mapper, @Value("${engine.wal.dir}") String walDir) {
        this.mapper = mapper;
        this.walDir = Path.of(walDir);
    }

    public void write(OrderBookRegistry registry, long lastSeq) throws IOException {
        Files.createDirectories(walDir);
        List<Snapshot.PairSnapshot> pairs = new ArrayList<>();
        registry.books().forEach((pair, book) -> pairs.add(
            new Snapshot.PairSnapshot(pair.exchangeCoinId(), new ArrayList<>(book.allOrders()))
        ));
        Snapshot snap = new Snapshot(lastSeq, pairs);
        Path tmp = walDir.resolve(SNAPSHOT_TMP_FILE);
        Path target = walDir.resolve(SNAPSHOT_FILE);
        try (FileOutputStream fos = new FileOutputStream(tmp.toFile())) {
            fos.write(mapper.writeValueAsBytes(snap));
            fos.getFD().sync();
        }
        try {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
        log.info("snapshot written seq={} pairs={}", lastSeq, pairs.size());
    }
}
