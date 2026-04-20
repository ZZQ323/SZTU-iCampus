package cn.edu.sztui.stream.application.activity.vo;

import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 LLM 返回的 JSON 能被正确反序列化为 {@link ActivityExtractionVo}。
 */
class ActivityExtractionVoTest {

    @Test
    void parses_full_activity_response() {
        String json = """
                {
                  "isActivity": true,
                  "confidence": 0.92,
                  "type": "讲座",
                  "title": "人工智能前沿讲座",
                  "startAt": "2026-04-28T14:00",
                  "endAt": "2026-04-28T16:00",
                  "location": "A101",
                  "registration": "扫码报名",
                  "summary": "邀请知名学者分享 AI 最新进展。"
                }
                """;

        ActivityExtractionVo vo = JSON.parseObject(json, ActivityExtractionVo.class);

        assertTrue(vo.isActivity());
        assertEquals(0.92, vo.getConfidence(), 0.001);
        assertEquals("讲座", vo.getType());
        assertEquals("人工智能前沿讲座", vo.getTitle());
        assertEquals("2026-04-28T14:00", vo.getStartAt());
        assertEquals("2026-04-28T16:00", vo.getEndAt());
        assertEquals("A101", vo.getLocation());
        assertEquals("扫码报名", vo.getRegistration());
        assertTrue(vo.getSummary().contains("AI"));
    }

    @Test
    void parses_non_activity_response() {
        String json = """
                {
                  "isActivity": false,
                  "confidence": 0.15,
                  "type": "",
                  "title": "",
                  "startAt": "",
                  "endAt": "",
                  "location": "",
                  "registration": "",
                  "summary": ""
                }
                """;

        ActivityExtractionVo vo = JSON.parseObject(json, ActivityExtractionVo.class);
        assertFalse(vo.isActivity());
        assertTrue(vo.getConfidence() < 0.5);
        assertEquals("", vo.getType());
        assertEquals("", vo.getTitle());
    }

    @Test
    void parses_partial_activity_with_missing_fields() {
        // 真实 LLM 有时漏字段 —— 应 gracefully 接受，缺的就是 null
        String json = """
                {
                  "isActivity": true,
                  "confidence": 0.7,
                  "type": "比赛",
                  "title": "程序设计竞赛"
                }
                """;

        ActivityExtractionVo vo = JSON.parseObject(json, ActivityExtractionVo.class);
        assertTrue(vo.isActivity());
        assertEquals("比赛", vo.getType());
        assertNull(vo.getLocation());
        assertNull(vo.getStartAt());
    }
}
