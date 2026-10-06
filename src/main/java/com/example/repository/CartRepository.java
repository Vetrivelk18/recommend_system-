package com.example.repository;

import com.example.entity.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * The cart, as native SQL.
 *
 * <p>Bound to Product only because Spring Data needs an entity type - cart_items has no
 * entity of its own, following the same pattern as last_search in SearchRepository. The
 * practical benefit is that ddl-auto=validate ignores the table, so a missing
 * schema/cart_orders.sql breaks cart requests rather than application startup.
 *
 * <p>JPA could not express the add() upsert anyway, which is the operation that makes a
 * second "add" increment a line instead of duplicating it.
 */
public interface CartRepository extends JpaRepository<Product, Integer> {

    interface CartLineRow {
        Integer getProductId();
        String getProduct();
        Integer getQuantity();
        Boolean getInStock();
    }

    /**
     * Product names are joined live rather than stored on the cart row, so a renamed or
     * de-stocked product is seen as it is now. in_stock comes along because checkout
     * refuses out-of-stock lines and the cart page should warn before the user gets there.
     *
     * <p>Ordered by added_at so the cart does not reshuffle as quantities change.
     */
    @Query(value = """
            SELECT ci.product_id AS productId,
                   p.product     AS product,
                   ci.quantity   AS quantity,
                   p.in_stock    AS inStock
            FROM cart_items ci
            JOIN products p ON p.product_id = ci.product_id
            WHERE ci.user_id = :userId
            ORDER BY ci.added_at
            """, nativeQuery = true)
    List<CartLineRow> lines(@Param("userId") Integer userId);

    /**
     * Add-or-increment in one statement. Two taps on Add cannot race and lose one, which
     * a read-then-write would allow.
     *
     * <p>LEAST caps the result instead of failing: a user who holds the button down gets
     * 99, not a 500 from the check constraint.
     */
    @Modifying
    @Query(value = """
            INSERT INTO cart_items (user_id, product_id, quantity)
            VALUES (:userId, :productId, :quantity)
            ON CONFLICT (user_id, product_id) DO UPDATE
            SET quantity = LEAST(cart_items.quantity + EXCLUDED.quantity, :maxQuantity)
            """, nativeQuery = true)
    int add(@Param("userId") Integer userId,
            @Param("productId") Integer productId,
            @Param("quantity") int quantity,
            @Param("maxQuantity") int maxQuantity);

    /** Absolute set, not a delta - the cart page's quantity box. Zero is handled by removeLine. */
    @Modifying
    @Query(value = """
            UPDATE cart_items SET quantity = :quantity
            WHERE user_id = :userId AND product_id = :productId
            """, nativeQuery = true)
    int setQuantity(@Param("userId") Integer userId,
                    @Param("productId") Integer productId,
                    @Param("quantity") int quantity);

    @Modifying
    @Query(value = "DELETE FROM cart_items WHERE user_id = :userId AND product_id = :productId",
            nativeQuery = true)
    int removeLine(@Param("userId") Integer userId, @Param("productId") Integer productId);

    /** Also the last step of a checkout - see OrderService.place. */
    @Modifying
    @Query(value = "DELETE FROM cart_items WHERE user_id = :userId", nativeQuery = true)
    int clear(@Param("userId") Integer userId);
}
