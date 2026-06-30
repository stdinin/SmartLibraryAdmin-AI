package com.example.springboot.controller.request.ai;

import lombok.Data;

@Data
public class CatalogRequest {
    private String title;
    private String author;
    private String publisher;
    private String isbn;
    private String description;
}
