package com.example.demo.bi.web;

import com.example.demo.bi.service.BiDataSeeder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** 假数据生成/重造入口：POST /api/bi/seed?force=true 清空后重建 */
@RestController
@RequestMapping("/api/bi/seed")
public class BiSeedController {

    private final BiDataSeeder dataSeeder;

    public BiSeedController(BiDataSeeder dataSeeder) {
        this.dataSeeder = dataSeeder;
    }

    @PostMapping
    public Map<String, Object> seed(@RequestParam(defaultValue = "false") boolean force) {
        Map<String, Object> resp = new LinkedHashMap<>(dataSeeder.seed(force));
        resp.put("force", force);
        return resp;
    }
}
