package com.example.repository;

import com.example.entity.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ProductRepository extends JpaRepository<Product, Integer> {

    /**
     * Ranked by distinct buyers first, then total units, then last_bought.
     * Buyer count is the better popularity signal: total units is skewed by a few
     * heavy repeat buyers, so the two orderings genuinely differ (Organic Avocado
     * outranks Organic Hass Avocado on buyers, and loses on units).
     *
     * ~790ms per call - count(DISTINCT user_id) over 836k rows is the cost.
     */
    @Query(value = """
            WITH grouped AS (
                SELECT product_id,
                       sum(total_counts)       AS purchases,
                       count(DISTINCT user_id) AS buyers,
                       max(recent_order_id)    AS last_bought
                FROM user_products
                GROUP BY product_id
            )
            SELECT p.product
            FROM grouped g
            JOIN products p USING (product_id)
            WHERE p.in_stock
            ORDER BY g.buyers DESC, g.purchases DESC, g.last_bought DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<String> topOverall(@Param("limit") int limit);

    /**
     * Buy It Again: this user's own purchase history. Uses the user_products
     * composite PK (user_id, product_id) so it is an index scan - 0.1ms.
     *
     * recent_order_id as tiebreaker is inert: it is constant within a user
     * (198,026 of 198,032), so ordering effectively falls back to total_counts.
     */
    @Query(value = """
            SELECT p.product
            FROM user_products up
            JOIN products p ON up.product_id = p.product_id
            WHERE up.user_id = :userId
              AND p.in_stock
            ORDER BY up.total_counts DESC, up.recent_order_id DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<String> buyItAgain(@Param("userId") Integer userId, @Param("limit") int limit);

    /**
     * Running Low: items overdue relative to their OWN repurchase cycle.
     *
     * The ratio is the point, not the difference. Milk on a 3-order cycle and rice
     * on a 20-order cycle, both last bought 5 orders ago, are not equally due -
     * dividing by avg_gap is what separates them.
     *
     * Computed once in a subquery so "factor" can be referenced by name in both
     * WHERE and ORDER BY. A SELECT alias is not visible to WHERE in the same
     * query level - WHERE is evaluated before the select list exists.
     *
     * avg_gap is real, so this is floating-point division, not integer.
     */
    @Query(value = """
            SELECT t.product
            FROM (
                SELECT p.product,
                       (c.current_order_number - gd.last_order_num) / gd.avg_gap AS factor
                FROM gap_details gd
                JOIN current_order c ON c.user_id = gd.user_id
                JOIN products p      ON p.product_id = gd.product_id
                WHERE gd.user_id = :userId
                  AND p.in_stock
            ) t
            WHERE t.factor >= 1
            ORDER BY t.factor DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<String> runningLow(@Param("userId") Integer userId, @Param("limit") int limit);
}
