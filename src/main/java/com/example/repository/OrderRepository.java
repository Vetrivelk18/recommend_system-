package com.example.repository;

import com.example.entity.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Orders, plus the three derived tables a real purchase has to move.
 *
 * <p>That second part is the non-obvious half. gap_details and current_order are BASE
 * TABLES, not views (checked 2026-09-30), so they are materialised snapshots of the
 * Instacart history and nothing updates them on its own. Writing an order without
 * touching them leaves "Running Low" permanently recommending what the user just bought.
 *
 * <p>Same reason as CartRepository for the native SQL and the Product binding: no entity,
 * so ddl-auto=validate ignores the new tables.
 */
public interface OrderRepository extends JpaRepository<Product, Integer> {

    // ---------------------------------------------------------------- writing an order

    /**
     * The id is taken from the sequence up front rather than read back after the insert.
     * lastval() would also work, but only because it happens to share the connection -
     * this does not depend on that.
     */
    @Query(value = "SELECT nextval('orders_order_id_seq')", nativeQuery = true)
    Long nextOrderId();

    @Modifying
    @Query(value = """
            INSERT INTO orders (order_id, user_id, total_quantity)
            VALUES (:orderId, :userId, :totalQuantity)
            """, nativeQuery = true)
    int insertOrder(@Param("orderId") Long orderId,
                    @Param("userId") Integer userId,
                    @Param("totalQuantity") int totalQuantity);

    /**
     * Copies the cart into order_items in one statement, snapshotting each product name as
     * it reads it. Doing this in Java would be a round trip per line and would snapshot
     * names the client sent rather than names the database holds.
     */
    @Modifying
    @Query(value = """
            INSERT INTO order_items (order_id, product_id, product_name, quantity)
            SELECT :orderId, ci.product_id, p.product, ci.quantity
            FROM cart_items ci
            JOIN products p ON p.product_id = ci.product_id
            WHERE ci.user_id = :userId
            """, nativeQuery = true)
    int copyCartToOrder(@Param("orderId") Long orderId, @Param("userId") Integer userId);

    // ------------------------------------------------- keeping the dashboard rails honest

    /**
     * Advances the user's order clock, creating it at 1 for someone who has never had one.
     *
     * <p>This number is the unit "Running Low" measures in - factor is
     * (current_order_number - last_order_num) / avg_gap - so it has to move before
     * rollForwardGaps runs.
     */
    @Modifying
    @Query(value = """
            INSERT INTO current_order (user_id, current_order_number)
            VALUES (:userId, 1)
            ON CONFLICT (user_id) DO UPDATE
            SET current_order_number = current_order.current_order_number + 1
            """, nativeQuery = true)
    int bumpOrderNumber(@Param("userId") Integer userId);

    @Query(value = "SELECT current_order_number FROM current_order WHERE user_id = :userId",
            nativeQuery = true)
    Integer currentOrderNumber(@Param("userId") Integer userId);

    /**
     * Rolls each purchased product's repurchase cycle forward.
     *
     * <p>UPDATE only - deliberately never an upsert. Every row in gap_details has
     * times_bought >= 3 and avg_gap >= 1 (checked 2026-10-02: 183,828 rows, none with
     * avg_gap of 0 or null). Inserting a first-purchase row would mean avg_gap 0, and
     * ProductRepository.runningLow divides by avg_gap - Postgres raises on float division
     * by zero, so that single row would break the whole rail for that user. A product
     * therefore joins the Running Low machinery only once the source data tracks it;
     * new products still reach the dashboard through user_products ("Buy it again").
     *
     * <p>The new average folds in the gap just observed, using times_bought as the count:
     * (avg * (n-1) + newGap) / n. Without this, bumping the order clock alone would make
     * every item the user just bought look MORE overdue than before they bought it.
     *
     * <p>The last guard keeps newGap positive, which is what keeps avg_gap above zero and
     * the invariant above intact.
     */
    @Modifying
    @Query(value = """
            UPDATE gap_details g
            SET avg_gap = (g.avg_gap * (g.times_bought - 1)
                            + (:orderNumber - g.last_order_num)) / g.times_bought,
                times_bought = g.times_bought + 1,
                last_order_num = :orderNumber
            WHERE g.user_id = :userId
              AND g.product_id IN (SELECT product_id FROM cart_items WHERE user_id = :userId)
              AND :orderNumber > g.last_order_num
            """, nativeQuery = true)
    int rollForwardGaps(@Param("userId") Integer userId, @Param("orderNumber") Integer orderNumber);

    /**
     * Folds the order into the purchase history behind "Buy it again", and behind the
     * exclusion in CandidateRepository.behavioural - so a just-bought product also stops
     * being recommended.
     *
     * <p>recent_order_id is set to our own order number, which is a different id space
     * from the Instacart order ids already in the column. It is only ever used as a
     * tiebreaker (ProductRepository.buyItAgain, topOverall), so the effect is cosmetic.
     */
    @Modifying
    @Query(value = """
            INSERT INTO user_products (user_id, product_id, total_counts, recent_order_id)
            SELECT :userId, ci.product_id, ci.quantity, :orderNumber
            FROM cart_items ci
            WHERE ci.user_id = :userId
            ON CONFLICT (user_id, product_id) DO UPDATE
            SET total_counts    = user_products.total_counts + EXCLUDED.total_counts,
                recent_order_id = EXCLUDED.recent_order_id
            """, nativeQuery = true)
    int mergeIntoHistory(@Param("userId") Integer userId, @Param("orderNumber") Integer orderNumber);

    // ------------------------------------------------------------------- reading orders

    interface OrderRow {
        Long getOrderId();
        /**
         * Instant, not OffsetDateTime: a native timestamptz arrives as an Instant and
         * Spring Data has no converter between the two, so declaring OffsetDateTime here
         * fails at runtime with "Cannot project java.time.Instant". Entity mappings (see
         * User.createdAt) go through Hibernate's type system instead and are unaffected.
         */
        Instant getPlacedAt();
        String getStatus();
        Integer getTotalQuantity();
        Integer getLineCount();
    }

    interface OrderLineRow {
        Integer getProductId();
        String getProductName();
        Integer getQuantity();
    }

    @Query(value = """
            SELECT o.order_id       AS orderId,
                   o.placed_at      AS placedAt,
                   o.status         AS status,
                   o.total_quantity AS totalQuantity,
                   (SELECT count(*) FROM order_items oi WHERE oi.order_id = o.order_id) AS lineCount
            FROM orders o
            WHERE o.user_id = :userId
            ORDER BY o.placed_at DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<OrderRow> ordersFor(@Param("userId") Integer userId, @Param("limit") int limit);

    /** user_id is in the WHERE so one user cannot read another's order by guessing an id. */
    @Query(value = """
            SELECT o.order_id       AS orderId,
                   o.placed_at      AS placedAt,
                   o.status         AS status,
                   o.total_quantity AS totalQuantity,
                   (SELECT count(*) FROM order_items oi WHERE oi.order_id = o.order_id) AS lineCount
            FROM orders o
            WHERE o.order_id = :orderId AND o.user_id = :userId
            """, nativeQuery = true)
    Optional<OrderRow> findOrder(@Param("userId") Integer userId, @Param("orderId") Long orderId);

    @Query(value = """
            SELECT product_id   AS productId,
                   product_name AS productName,
                   quantity     AS quantity
            FROM order_items
            WHERE order_id = :orderId
            ORDER BY product_name
            """, nativeQuery = true)
    List<OrderLineRow> linesFor(@Param("orderId") Long orderId);
}
