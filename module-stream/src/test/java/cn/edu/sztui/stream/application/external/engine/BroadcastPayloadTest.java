package cn.edu.sztui.stream.application.external.engine;

import cn.edu.sztui.stream.infrastructure.persistence.parser.strategy.ListParserResult.InfoItemMeta;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 WS 推送 payload 契约（CLAUDE.md 硬规则）：
 * <ul>
 *   <li>必须带 {@code items: List<InfoItemMeta>} 完整字段</li>
 *   <li>{@code items} 里每条必须有 title/author/publishDate（不是骨架）</li>
 *   <li>{@code ids} 保留向后兼容</li>
 *   <li>{@code latestTitle/sourceId/sourceOrgName} 取自 head item 用于 toast</li>
 * </ul>
 *
 * 这是论文"流式推送"声明的可执行证据。
 * 如果未来某次提交把 items 字段砍掉退化成"只推 id"，这个测试会立刻失败。
 */
class BroadcastPayloadTest {

    @Test
    void payload_must_carry_full_items_not_just_ids() {
        InfoItemMeta a = InfoItemMeta.builder()
                .id("1234").title("关于春季开课的通知").author("彭凯风")
                .publishDate("2026-03-16").sourceId("gwt-jiaowu")
                .sourceOrgName("教务部").channelId("announcement")
                .build();
        InfoItemMeta b = InfoItemMeta.builder()
                .id("1233").title("课程停开").author("张三")
                .publishDate("2026-03-15").sourceId("gwt-jiaowu")
                .sourceOrgName("教务部").channelId("announcement")
                .build();

        Map<String, Object> payload = CrawlEngine.buildBroadcastPayload(
                "announcement", List.of(a, b), "1234");

        // 1) 必须有 items 完整列表
        assertNotNull(payload.get("items"), "payload 必须带 items 字段（流式推送硬规则）");
        @SuppressWarnings("unchecked")
        List<InfoItemMeta> items = (List<InfoItemMeta>) payload.get("items");
        assertEquals(2, items.size(), "items 数量必须等于 newItems");

        // 2) items 内容是完整的，不是骨架
        InfoItemMeta first = items.get(0);
        assertEquals("1234", first.getId());
        assertEquals("关于春季开课的通知", first.getTitle());
        assertEquals("彭凯风", first.getAuthor(), "author 必须保留 —— 否则前端只能再去 HTTP 拉");
        assertEquals("2026-03-16", first.getPublishDate());
        assertEquals("gwt-jiaowu", first.getSourceId());
        assertEquals("教务部", first.getSourceOrgName());

        // 3) 向后兼容字段
        assertEquals("announcement", payload.get("channelId"));
        assertEquals(2, payload.get("count"));
        assertEquals("1234", payload.get("latestId"));
        assertEquals(List.of("1234", "1233"), payload.get("ids"));

        // 4) head item 便利字段（toast 用）
        assertEquals("关于春季开课的通知", payload.get("latestTitle"));
        assertEquals("gwt-jiaowu", payload.get("sourceId"));
        assertEquals("教务部", payload.get("sourceOrgName"));
    }

    @Test
    void payload_with_empty_items_still_well_formed() {
        Map<String, Object> payload = CrawlEngine.buildBroadcastPayload(
                "announcement", List.of(), null);

        assertEquals("announcement", payload.get("channelId"));
        assertEquals(0, payload.get("count"));
        assertNotNull(payload.get("items"));
        assertTrue(((List<?>) payload.get("items")).isEmpty());
        // 空时不存在 head item 衍生字段
        assertNull(payload.get("latestTitle"));
        assertNull(payload.get("sourceId"));
    }
}
