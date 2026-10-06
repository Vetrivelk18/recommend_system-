package com.example.service;

import com.example.dto.CartLine;
import com.example.dto.CartView;
import com.example.entity.Product;
import com.example.repository.CartRepository;
import com.example.repository.ProductRepository;
import com.example.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Cart rules, kept out of the repository so the SQL stays declarative.
 *
 * <p>Three of them: the user and product must exist (a foreign key violation would
 * otherwise surface as a 500), the product must be in stock to be added at all, and a
 * quantity of zero is a removal rather than a zero-quantity row.
 */
@Service
public class CartService {

    /** Also enforced by the cart_items check constraint and by LEAST() in the upsert. */
    public static final int MAX_QUANTITY = 99;

    private final CartRepository cartRepository;
    private final ProductRepository productRepository;
    private final UserRepository userRepository;

    public CartService(CartRepository cartRepository,
                       ProductRepository productRepository,
                       UserRepository userRepository) {
        this.cartRepository = cartRepository;
        this.productRepository = productRepository;
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public CartView view(Integer userId) {
        requireUser(userId);
        return read(userId);
    }

    @Transactional
    public CartView add(Integer userId, Integer productId, int quantity) {
        requireUser(userId);

        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new UnknownProductException(productId));
        if (!Boolean.TRUE.equals(product.getInStock())) {
            throw new ProductOutOfStockException(product.getProduct());
        }

        cartRepository.add(userId, productId, quantity, MAX_QUANTITY);
        return read(userId);
    }

    /**
     * Zero removes the line. An out-of-stock product can still be reduced or removed -
     * only adding is blocked, so a cart that went stale is not stuck.
     */
    @Transactional
    public CartView setQuantity(Integer userId, Integer productId, int quantity) {
        requireUser(userId);

        if (quantity <= 0) {
            cartRepository.removeLine(userId, productId);
        } else if (cartRepository.setQuantity(userId, productId, quantity) == 0) {
            // Nothing updated means there is no such line - a PATCH cannot create one.
            throw new NoSuchCartLineException(productId);
        }
        return read(userId);
    }

    @Transactional
    public CartView remove(Integer userId, Integer productId) {
        requireUser(userId);
        cartRepository.removeLine(userId, productId);
        return read(userId);
    }

    @Transactional
    public CartView clear(Integer userId) {
        requireUser(userId);
        cartRepository.clear(userId);
        return read(userId);
    }

    private CartView read(Integer userId) {
        List<CartLine> lines = cartRepository.lines(userId).stream()
                .map(row -> new CartLine(
                        row.getProductId(),
                        row.getProduct(),
                        row.getQuantity(),
                        Boolean.TRUE.equals(row.getInStock())))
                .toList();
        return CartView.of(userId, lines);
    }

    private void requireUser(Integer userId) {
        if (!userRepository.existsById(userId)) {
            throw new UnknownUserException(userId);
        }
    }

    public static class UnknownUserException extends RuntimeException {
        public UnknownUserException(Integer userId) {
            super("no such user: " + userId);
        }
    }

    public static class UnknownProductException extends RuntimeException {
        public UnknownProductException(Integer productId) {
            super("no such product: " + productId);
        }
    }

    public static class ProductOutOfStockException extends RuntimeException {
        public ProductOutOfStockException(String product) {
            super("out of stock: " + product);
        }
    }

    public static class NoSuchCartLineException extends RuntimeException {
        public NoSuchCartLineException(Integer productId) {
            super("product " + productId + " is not in the cart");
        }
    }
}
