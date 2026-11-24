package io.github.middlewarelab.mybatis;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;

class StockUpdateTest {
    private static final String SKU = "sku-42";

    @Test
    void onlyOneOfTwoBuyersCanReserveTheLastItem() throws Exception {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:stock");
        try (var setup = source.getConnection(); var statement = setup.createStatement()) {
            statement.execute("CREATE TABLE stock (sku VARCHAR(32) PRIMARY KEY, qty INT NOT NULL CHECK (qty >= 0))");
            statement.execute("INSERT INTO stock (sku, qty) VALUES ('sku-42', 1)");

            var config = new Configuration(new Environment("lab", new JdbcTransactionFactory(), source));
            config.addMapper(StockMapper.class);
            var sessions = new SqlSessionFactoryBuilder().build(config);

            var readyToUpdate = new CyclicBarrier(2);
            var workers = Executors.newFixedThreadPool(2);
            try {
                var first = workers.submit(() -> reserve(sessions, readyToUpdate));
                var second = workers.submit(() -> reserve(sessions, readyToUpdate));
                var affectedRows = List.of(first.get(10, SECONDS), second.get(10, SECONDS));
                assertEquals(List.of(0, 1), affectedRows.stream().sorted().toList());
            } finally {
                workers.shutdownNow();
            }

            try (var session = sessions.openSession(true)) {
                var stock = session.getMapper(StockMapper.class);
                assertEquals(0, stock.reserve(SKU, -1), "negative quantity must not add to stock");
                assertEquals(0, stock.readQuantity(SKU));
            }
            System.out.println("Two buyers, one item: one UPDATE affected 1 row, the other 0; remaining stock=0");
        }
    }

    private int reserve(SqlSessionFactory sessions, CyclicBarrier readyToUpdate) throws Exception {
        try (var session = sessions.openSession(true)) {
            session.getConnection();
            readyToUpdate.await(5, SECONDS);
            return session.getMapper(StockMapper.class).reserve(SKU, 1);
        }
    }

    interface StockMapper {
        @Update("""
                UPDATE stock
                SET qty = qty - #{quantity}
                WHERE sku = #{sku} AND #{quantity} > 0 AND qty >= #{quantity}
                """)
        int reserve(@Param("sku") String sku, @Param("quantity") int quantity);

        @Select("SELECT qty FROM stock WHERE sku = #{sku}")
        int readQuantity(@Param("sku") String sku);
    }
}
