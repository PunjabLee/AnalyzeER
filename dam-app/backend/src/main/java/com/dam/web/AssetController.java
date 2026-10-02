package com.dam.web;

import com.dam.domain.MetaAsset;
import com.dam.domain.MetaColumn;
import com.dam.repository.MetaAssetRepository;
import com.dam.repository.MetaColumnRepository;
import com.dam.web.dto.Dtos.AssetDetail;
import com.dam.web.dto.Dtos.AssetSummary;
import com.dam.web.dto.Dtos.ColumnView;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/assets")
public class AssetController {

    private final MetaAssetRepository assetRepo;
    private final MetaColumnRepository columnRepo;

    public AssetController(MetaAssetRepository assetRepo, MetaColumnRepository columnRepo) {
        this.assetRepo = assetRepo;
        this.columnRepo = columnRepo;
    }

    @GetMapping
    public List<AssetSummary> list(@RequestParam(required = false) String keyword,
                                   @RequestParam(defaultValue = "200") int limit) {
        List<MetaAsset> assets = (keyword == null || keyword.isBlank())
                ? assetRepo.findAllByOrderByNameAsc()
                : assetRepo.findByNameContainingIgnoreCaseOrderByNameAsc(keyword.trim());
        return assets.stream().limit(limit).map(AssetController::toSummary).toList();
    }

    @GetMapping("/count")
    public long count() {
        return assetRepo.count();
    }

    @GetMapping("/by-name/{name}")
    public ResponseEntity<AssetDetail> byName(@PathVariable String name) {
        return assetRepo.findAllByOrderByNameAsc().stream()
                .filter(a -> a.getName().equalsIgnoreCase(name))
                .findFirst()
                .map(this::toDetail)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/detail")
    public ResponseEntity<AssetDetail> detail(@RequestParam String urn) {
        return assetRepo.findByAssetUrn(urn)
                .map(this::toDetail)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private AssetDetail toDetail(MetaAsset a) {
        List<ColumnView> cols = columnRepo.findByAssetIdOrderByOrdinalAsc(a.getId()).stream()
                .map(AssetController::toColumn)
                .toList();
        return new AssetDetail(toSummary(a), cols);
    }

    static AssetSummary toSummary(MetaAsset a) {
        return new AssetSummary(a.getAssetUrn(), a.getName(), a.getGrading(), a.getDomainCode(),
                a.getPrefixFamily(), a.getHasPk(), a.getColumnCount(), a.getTableComment());
    }

    static ColumnView toColumn(MetaColumn c) {
        return new ColumnView(c.getOrdinal(), c.getName(), c.getType(), c.getNullable(),
                c.getDefaultVal(), c.getKeyHint(), c.getMeaning(), c.getSourceLayer());
    }
}
