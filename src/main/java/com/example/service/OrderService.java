package com.example.service;

import com.example.dto.CartLine;
import com.example.dto.CartView;
import com.example.dto.OrderDetail;
import com.example.dto.OrderLine;
import com.example.dto.OrderSummary;
import com.example.repository.OrderRepository;
import com.example.repository.CartRepository;
import com.example.repository.OrderRepository.OrderRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Checkout, and reading back what was ordered.
 *
 * <p>place() is one transaction doing six writes, in this order for a reason: the order
 * header and lines first (so the record exists), then the user's order clock, then the two
 * derived tables that depend on that clock, then the cart is emptied. A failure anywhere
 * rolls the lot back and the user still has their cart.
 *
 * <p>The clock and the two derived tables are the part that is easy to miss.
 * current_order and gap_details are base tables, not views, so without those writes a
 * placed order would never reach "Running Low" or "Buy it again" - the dashboard would
 * keep suggesting what the user had just bought.
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private static final int HISTORY_LIMIT = 20;

    private final OrderRepository orderRepository;
    private final CartRepository cartRepository;
    private final CartService cartService;
    private final RecommendationCacheService recommendationCache;

    public OrderService(OrderRepository orderRepository,
                        CartRepository cartRepository,
                        CartService cartService,
                        RecommendationCacheService recommendationCache) {
        this.orderRepository = orderRepository;
        this.cartRepository = cartRepository;
        this.cartService = cartService;
        this.recommendationCache = recommendationCache;
    }

    @Transactional
    public OrderDetail place(Integer userId) {
        CartView cart = cartService.view(userId);
        if (cart.lines().isEmpty()) {
            throw new EmptyCartException();
        }

        // Checked rather than silently dropped: a disappearing line at checkout is
        // exactly the moment a shopper wants to be told, not to find out from a receipt.
        List<String> unavailable = cart.lines().stream()
                .filter(line -> !line.inStock())
                .map(CartLine::product)
                .toList();
        if (!unavailable.isEmpty()) {
            throw new OutOfStockException(unavailable);
        }

        Long orderId = orderRepository.nextOrderId();
        orderRepository.insertOrder(orderId, userId, cart.totalQuantity());
        orderRepository.copyCartToOrder(orderId, userId);

        orderRepository.bumpOrderNumber(userId);
        Integer orderNumber = orderRepository.currentOrderNumber(userId);

        // Order matters: both of these read cart_items, so they must run before the clear.
        int cyclesMoved = orderRepository.rollForwardGaps(userId, orderNumber);
        orderRepository.mergeIntoHistory(userId, orderNumber);

        cartRepository.clear(userId);

        // The cached picks were computed from a history that did not include this order.
        recommendationCache.evict(userId);

        log.info("order {} placed for user {}: {} lines, {} units, order number {}, {} repurchase cycles moved",
                orderId, userId, cart.lineCount(), cart.totalQuantity(), orderNumber, cyclesMoved);

        return detail(userId, orderId);
    }

    @Transactional(readOnly = true)
    public List<OrderSummary> history(Integer userId) {
        return orderRepository.ordersFor(userId, HISTORY_LIMIT).stream()
                .map(OrderService::summary)
                .toList();
    }

    @Transactional(readOnly = true)
    public OrderDetail detail(Integer userId, Long orderId) {
        OrderRow row = orderRepository.findOrder(userId, orderId)
                .orElseThrow(() -> new UnknownOrderException(orderId));

        List<OrderLine> lines = orderRepository.linesFor(orderId).stream()
                .map(line -> new OrderLine(
                        line.getProductId(), line.getProductName(), line.getQuantity()))
                .toList();

        return new OrderDetail(summary(row), lines);
    }

    private static OrderSummary summary(OrderRow row) {
        return new OrderSummary(
                row.getOrderId(),
                row.getPlacedAt(),
                row.getStatus(),
                row.getTotalQuantity(),
                row.getLineCount());
    }

    public static class EmptyCartException extends RuntimeException {
        public EmptyCartException() {
            super("cart is empty");
        }
    }

    public static class OutOfStockException extends RuntimeException {
        private final List<String> products;

        public OutOfStockException(List<String> products) {
            super("out of stock: " + String.join(", ", products));
            this.products = products;
        }

        public List<String> getProducts() {
            return products;
        }
    }

    public static class UnknownOrderException extends RuntimeException {
        public UnknownOrderException(Long orderId) {
            super("no such order: " + orderId);
        }
    }
}
