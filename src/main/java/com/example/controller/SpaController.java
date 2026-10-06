package com.example.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Angular routes are client-side, so Spring has no file at /login or /dashboard
 * and would return 404 on a refresh or a direct link. Forward them to index.html
 * and let the Angular router take over.
 *
 * Listed explicitly rather than using a catch-all, so /api/** can never be
 * swallowed by the forward.
 */
@Controller
public class SpaController {

    @GetMapping({"/", "/login", "/signup", "/dashboard", "/search", "/cart", "/orders"})
    public String forwardToApp() {
        return "forward:/index.html";
    }
}
