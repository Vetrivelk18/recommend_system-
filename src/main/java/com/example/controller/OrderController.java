package com.example.controller;

import com.example.dto.OrderDetail;
import com.example.dto.OrderSummary;
import com.example.service.CartService;
import com.example.service.OrderService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Placing an order is a POST to the collection with no body - the cart IS the body. There
 * is no way to order something that is not in the cart, which keeps the stock and quantity
 * rules in one place.
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping("/{userId}")
    public ResponseEntity<OrderDetail> place(@PathVariable Integer userId) {
        return ResponseEntity.status(HttpStatus.CREATED).body(orderService.place(userId));
    }

    @GetMapping("/{userId}")
    public List<OrderSummary> history(@PathVariable Integer userId) {
        return orderService.history(userId);
    }

    @GetMapping("/{userId}/{orderId}")
    public OrderDetail detail(@PathVariable Integer userId, @PathVariable Long orderId) {
        return orderService.detail(userId, orderId);
    }

    @ExceptionHandler(OrderService.UnknownOrderException.class)
    public ResponseEntity<Map<String, String>> handleUnknownOrder(OrderService.UnknownOrderException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(CartService.UnknownUserException.class)
    public ResponseEntity<Map<String, String>> handleUnknownUser(CartService.UnknownUserException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(OrderService.EmptyCartException.class)
    public ResponseEntity<Map<String, String>> handleEmptyCart(OrderService.EmptyCartException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }

    /** Names the offending products so the page can point at the lines to remove. */
    @ExceptionHandler(OrderService.OutOfStockException.class)
    public ResponseEntity<Map<String, Object>> handleOutOfStock(OrderService.OutOfStockException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("error", e.getMessage(), "products", e.getProducts()));
    }
}
