package com.example.dto;

import java.util.List;

public record OrderDetail(OrderSummary order, List<OrderLine> lines) {
}
