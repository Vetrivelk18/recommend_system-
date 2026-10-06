package com.example.controller;

import com.example.dto.AddToCartRequest;
import com.example.dto.CartView;
import com.example.dto.UpdateQuantityRequest;
import com.example.service.CartService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * userId is a path variable, matching /api/dashboard/{userId} and
 * /api/recommendations/{userId}. Unlike those, these endpoints WRITE - so the absence of a
 * JWT filter (see SecurityBeans) means any caller can alter any user's cart. Consistent
 * with the rest of the app today, and the thing to fix first when authentication arrives.
 *
 * <p>Every mutating method returns the full cart, so the client's badge stays correct
 * without a follow-up GET.
 */
@RestController
@RequestMapping("/api/cart")
public class CartController {

    private final CartService cartService;

    public CartController(CartService cartService) {
        this.cartService = cartService;
    }

    @GetMapping("/{userId}")
    public CartView cart(@PathVariable Integer userId) {
        return cartService.view(userId);
    }

    @PostMapping("/{userId}/items")
    public CartView add(@PathVariable Integer userId,
                        @Valid @RequestBody AddToCartRequest request) {
        return cartService.add(userId, request.productId(), request.quantityOrOne());
    }

    @PatchMapping("/{userId}/items/{productId}")
    public CartView setQuantity(@PathVariable Integer userId,
                                @PathVariable Integer productId,
                                @Valid @RequestBody UpdateQuantityRequest request) {
        return cartService.setQuantity(userId, productId, request.quantity());
    }

    @DeleteMapping("/{userId}/items/{productId}")
    public CartView remove(@PathVariable Integer userId, @PathVariable Integer productId) {
        return cartService.remove(userId, productId);
    }

    @DeleteMapping("/{userId}")
    public CartView clear(@PathVariable Integer userId) {
        return cartService.clear(userId);
    }

    @ExceptionHandler(CartService.UnknownUserException.class)
    public ResponseEntity<Map<String, String>> handleUnknownUser(CartService.UnknownUserException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(CartService.UnknownProductException.class)
    public ResponseEntity<Map<String, String>> handleUnknownProduct(CartService.UnknownProductException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(CartService.NoSuchCartLineException.class)
    public ResponseEntity<Map<String, String>> handleNoSuchLine(CartService.NoSuchCartLineException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    /** 409 rather than 400: the request was well formed, the catalogue moved. */
    @ExceptionHandler(CartService.ProductOutOfStockException.class)
    public ResponseEntity<Map<String, String>> handleOutOfStock(CartService.ProductOutOfStockException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }
}
