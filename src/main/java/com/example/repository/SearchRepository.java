package com.example.repository;

import com.example.entity.Product;
import com.example.repository.CandidateRepository.CandidateRow;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * The real search box: RRF over whatever text the user actually typed, fused between
 * text_emb cosine similarity and name_tsv full-text search. This is the same fusion
 * as ml/search.py, ported to native SQL - no purchase-history substitution, because
 * a search box has a real query string to work with.
 *
 * <p>Unlike the candidate-generation arms in CandidateRepository, this does NOT
 * exclude the user's past purchases - someone searching "milk" while shopping wants
 * to see milk whether or not they have bought it before.
 *
 * <p>plainto_tsquery is correct here (unlike the purchase-history lexeme arm, which
 * had to avoid it): a 1-4 word typed search SHOULD require all its words to match,
 * which is exactly what plainto_tsquery's implicit AND gives you.
 */
public interface SearchRepository extends JpaRepository<Product, Integer> {

    /**
     * Each arm is ranked and LIMITed inside its own subquery before row_number() is
     * applied - row_number() runs before ORDER BY/LIMIT in the same SELECT, so
     * numbering there gives scan-order positions, not ranks. Getting this wrong is
     * silent: RRF still returns rows, just with one arm weighted into irrelevance.
     */
    @Query(value = """
            WITH sem AS (
                SELECT product_id, row_number() OVER () AS rank
                FROM (
                    SELECT product_id
                    FROM products
                    WHERE text_emb IS NOT NULL AND in_stock
                    ORDER BY text_emb <=> CAST(:vec AS vector)
                    LIMIT :candidates
                ) ordered
            ),
            lex AS (
                SELECT product_id, row_number() OVER () AS rank
                FROM (
                    SELECT product_id
                    FROM products, plainto_tsquery('english', :q) tsq
                    WHERE name_tsv @@ tsq AND in_stock
                    ORDER BY ts_rank_cd(name_tsv, tsq) DESC
                    LIMIT :candidates
                ) ordered
            )
            SELECT p.product_id AS productId, p.product AS product
            FROM products p
            LEFT JOIN sem ON sem.product_id = p.product_id
            LEFT JOIN lex ON lex.product_id = p.product_id
            WHERE sem.rank IS NOT NULL OR lex.rank IS NOT NULL
            ORDER BY COALESCE(1.0 / (60 + sem.rank), 0)
                   + COALESCE(1.0 / (60 + lex.rank), 0) DESC,
                   p.product_id
            LIMIT :limit
            """, nativeQuery = true)
    List<CandidateRow> rrf(@Param("vec") String vectorLiteral,
                          @Param("q") String queryText,
                          @Param("candidates") int candidatesPerArm,
                          @Param("limit") int limit);

    /**
     * Overwrites the user's stored search - "last search" is deliberately singular,
     * not a history. The webhook only ever needs the most recent one, and keeping a
     * history would need its own cleanup policy for no benefit here.
     *
     * <p>productIds arrives as a literal ("{1,2,3}") rather than a bound array for
     * the same reason vectors are passed as literal strings elsewhere in this
     * project: it sidesteps driver/dialect-specific array binding and keeps every
     * "build a literal, cast in SQL" case in the codebase consistent.
     */
    @Modifying
    @Query(value = """
            INSERT INTO last_search (user_id, query_text, product_ids, searched_at)
            VALUES (:userId, :queryText, CAST(:productIds AS int[]), now())
            ON CONFLICT (user_id) DO UPDATE
            SET query_text = EXCLUDED.query_text,
                product_ids = EXCLUDED.product_ids,
                searched_at = now()
            """, nativeQuery = true)
    void saveLastSearch(@Param("userId") Integer userId,
                        @Param("queryText") String queryText,
                        @Param("productIds") String productIdsLiteral);

    /**
     * The query this user last searched, read before saveLastSearch overwrites it.
     * Lets the caller tell a repeated search from a changed one - the two produce the
     * same stored pool, so only a changed one invalidates anything downstream.
     */
    @Query(value = """
            SELECT query_text FROM last_search WHERE user_id = :userId
            """, nativeQuery = true)
    Optional<String> currentQueryText(@Param("userId") Integer userId);

    /**
     * Users whose stored search is old enough for the webhook job to act on, and
     * have not already been notified for THIS search. last_search is one row per
     * user (see saveLastSearch), so the guard is webhook_sent_at < searched_at -
     * without it, every poll after the 15-minute mark would resend forever.
     *
     * <p>Requires last_search.webhook_sent_at (timestamptz, nullable) - added by hand,
     * not through ddl-auto, since no JPA entity maps this table.
     */
    @Query(value = """
            SELECT user_id
            FROM last_search
            WHERE searched_at <= now() - (:delayMinutes || ' minutes')::interval
              AND (webhook_sent_at IS NULL OR webhook_sent_at < searched_at)
            """, nativeQuery = true)
    List<Integer> dueForWebhook(@Param("delayMinutes") int delayMinutes);

    /**
     * Marks the current search as notified, so dueForWebhook stops returning it.
     *
     * <p>@Transactional here, not just on the caller: unlike saveLastSearch (called
     * from SearchService, which already runs in a transaction), WebhookJob has no
     * transactional context of its own, and a @Modifying query throws
     * InvalidDataAccessApiUsageException without one - Spring Data does not open a
     * transaction for custom @Query methods the way it does for inherited CRUD ones.
     */
    @Transactional
    @Modifying
    @Query(value = """
            UPDATE last_search SET webhook_sent_at = now() WHERE user_id = :userId
            """, nativeQuery = true)
    void markWebhookSent(@Param("userId") Integer userId);
}
