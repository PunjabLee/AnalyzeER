package com.dam.web;

import com.dam.domain.MetaDomain;
import com.dam.repository.MetaDomainRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/domains")
public class DomainController {

    private final MetaDomainRepository domainRepo;

    public DomainController(MetaDomainRepository domainRepo) {
        this.domainRepo = domainRepo;
    }

    @GetMapping
    public List<MetaDomain> list() {
        return domainRepo.findAll().stream()
                .sorted((x, y) -> x.getCode().compareTo(y.getCode()))
                .toList();
    }
}
