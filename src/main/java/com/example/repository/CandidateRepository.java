package com.example.repository;

import com.example.entity.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * The two retrieval arms feeding the reranker.
 *
 * <p>The behavioural arm searches with the user tower's own 128-d output, looked up
 * from user_embeddings. The query arm reads whatever SearchService last computed and
 * stored in last_search when the user actually searched - it does NOT re-embed
 * anything or recompute RRF here. RRF runs exactly once, at search time
 * (SearchService.search); the webhook job 15 minutes later just reads that stored
 * result. See SearchRepository for the RRF query itself.
 *
 * <p>A user who has never searched has no last_search row, so this arm is simply
 * empty for them - not an error, just nothing to contribute.
 */
public interface CandidateRepository extends JpaRepository<Product, Integer> {

    interface CandidateRow {
        Integer getProductId();
        String getProduct();
    }

    /**
     * Behavioural arm - the trained two-tower signal.
     *
     * <p>The 128-d vector is the user tower's forward pass, precomputed by
     * ml/export_user_embeddings.py because the tower needs TensorFlow and cannot run
     * in this process. Rerun that script whenever user_features changes; the vector is
     * allowed to be minutes stale, since the consumer is a batch job and a user's
     * feature row does not move within a session.
     *
     * <p>The CTE returns zero rows for a user with no stored embedding, so the cross
     * join yields nothing and the arm is simply empty - no NULL guard needed.
     *
     * <p>Both the history and cold paths are exported, so a user who only completed
     * onboarding still has a vector here.
     */
    @Query(value = """
            WITH taste AS (
                SELECT emb AS v FROM user_embeddings WHERE user_id = :userId
            )
            SELECT p.product_id AS productId, p.product AS product
            FROM products p, taste
            WHERE p.behavioral_emb IS NOT NULL
              AND p.in_stock
              AND p.product_id NOT IN (
                  SELECT product_id FROM user_products WHERE user_id = :userId)
            ORDER BY p.behavioral_emb <=> taste.v
            LIMIT :limit
            """, nativeQuery = true)
    List<CandidateRow> behavioural(@Param("userId") Integer userId, @Param("limit") int limit);

    /**
     * Query arm - reads the RRF result SearchService already computed and stored,
     * in the order RRF ranked it (WITH ORDINALITY preserves that order across the
     * unnest). No embedding call, no RRF computation, no database work beyond a
     * lookup happens here - by the time the webhook runs, the expensive part is
     * long done.
     */
    @Query(value = """
            SELECT p.product_id AS productId, p.product AS product
            FROM last_search ls
            JOIN LATERAL unnest(ls.product_ids) WITH ORDINALITY AS t(product_id, ord)
                ON true
            JOIN products p ON p.product_id = t.product_id
            WHERE ls.user_id = :userId
            ORDER BY t.ord
            LIMIT :limit
            """, nativeQuery = true)
    List<CandidateRow> query(@Param("userId") Integer userId, @Param("limit") int limit);

    /** What the user actually buys - the context line the reranker judges against. */
    @Query(value = """
            SELECT p.product
            FROM user_products up
            JOIN products p ON p.product_id = up.product_id
            WHERE up.user_id = :userId
            ORDER BY up.total_counts DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<String> purchaseHistory(@Param("userId") Integer userId, @Param("limit") int limit);
}
