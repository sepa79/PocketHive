package io.pockethive.tools.sqltocsv;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;

@DisplayName("Thread Safety and Performance Tests")
class ThreadSafetyPerformanceTest {

    @Test
    @DisplayName("SqlExportConfig is thread-safe (immutable)")
    void sqlExportConfigIsThreadSafe(@TempDir Path tempDir) throws Exception {
        File outputFile = tempDir.resolve("output.csv").toFile();
        
        SqlExportConfig config = SqlExportConfig.builder()
            .jdbcUrl("jdbc:h2:mem:test")
            .username("sa")
            .password("")
            .query("SELECT 1")
            .outputFile(outputFile)
            .build();
        
        ExecutorService executor = Executors.newFixedThreadPool(10);
        List<Future<String>> futures = new ArrayList<>();
        
        // Share config across 100 threads
        for (int i = 0; i < 100; i++) {
            futures.add(executor.submit(() -> {
                // All threads should see consistent state
                return config.jdbcUrl() + "|" + config.query() + "|" + config.fetchSize();
            }));
        }
        
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);
        
        // All threads see same values
        List<String> results = futures.stream().map(f -> {
            try {
                return f.get();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }).distinct().toList();
        
        assertThat(results).hasSize(1);
        assertThat(results.get(0)).contains("jdbc:h2:mem:test");
    }



    @Test
    @DisplayName("Validators are stateless and thread-safe")
    void validatorsAreThreadSafe() throws Exception {
        io.pockethive.tools.sqltocsv.security.QueryValidator validator = 
            new io.pockethive.tools.sqltocsv.security.QueryValidator();
        
        ExecutorService executor = Executors.newFixedThreadPool(10);
        List<Future<Boolean>> futures = new ArrayList<>();
        
        // Share validator across threads
        for (int i = 0; i < 100; i++) {
            futures.add(executor.submit(() -> {
                validator.validate("SELECT * FROM users");
                return true;
            }));
        }
        
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);
        
        // All validations succeed
        assertThat(futures).allMatch(f -> {
            try {
                return f.get();
            } catch (Exception e) {
                return false;
            }
        });
    }

    @Test
    @DisplayName("Validators can handle concurrent validation requests")
    void validatorsConcurrentValidation() throws Exception {
        io.pockethive.tools.sqltocsv.security.JdbcUrlValidator validator = 
            new io.pockethive.tools.sqltocsv.security.JdbcUrlValidator();
        
        ExecutorService executor = Executors.newFixedThreadPool(10);
        List<Future<Boolean>> futures = new ArrayList<>();
        
        for (int i = 0; i < 100; i++) {
            futures.add(executor.submit(() -> {
                validator.validateHost("localhost");
                return true;
            }));
        }
        
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);
        
        assertThat(futures).allMatch(f -> {
            try {
                return f.get();
            } catch (Exception e) {
                return false;
            }
        });
    }


}
