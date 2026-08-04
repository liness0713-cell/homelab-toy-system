package com.toysystem.search.controller;

import com.toysystem.search.document.PolicyDocument;
import com.toysystem.search.repository.PolicyDocumentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * 只读搜索API，CQRS的读路径——数据来自ES投影，不碰policy-service的MySQL。
 */
@RestController
@RequestMapping("/api/search/policies")
@RequiredArgsConstructor
public class SearchController {

    private final PolicyDocumentRepository repository;

    @GetMapping
    public List<PolicyDocument> search(@RequestParam(required = false) String q) {
        if (q == null || q.isBlank()) {
            List<PolicyDocument> all = new ArrayList<>();
            repository.findAll().forEach(all::add);
            return all;
        }
        return repository.search(q);
    }
}
