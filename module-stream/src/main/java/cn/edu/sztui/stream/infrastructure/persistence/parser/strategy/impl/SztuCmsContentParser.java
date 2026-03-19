package cn.edu.sztui.stream.infrastructure.persistence.parser.strategy.impl;

import cn.edu.sztui.stream.infrastructure.persistence.parser.config.CrawlerConfig.SourceConfig;
import cn.edu.sztui.stream.infrastructure.persistence.parser.strategy.ContentParserResult;
import cn.edu.sztui.stream.infrastructure.persistence.parser.strategy.ListParserResult;
import cn.edu.sztui.stream.infrastructure.persistence.parser.strategy.ParserStrategy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * CMS 详情解析器（委托给 SztuGwtContentParser）
 * <p>
 * 所有博达 CMS 站点（官网/教务/学院子站）的详情页结构与公文通完全一致：
 * h1.article-title + div.article-sm + #vsb_content .v_news_content
 * <p>
 * 本类仅用于让 ParserFactory 能通过 type="sztu-cms" 找到详情解析器。
 * 实际解析逻辑 100% 委托给 SztuGwtContentParser。
 */
@Slf4j
@Component
public class SztuCmsContentParser implements ParserStrategy {

    public static final String TYPE = "sztu-cms";

    @Resource
    private SztuGwtContentParser delegate;

    @Override
    public String getType() {
        return TYPE;
    }

    @Override
    public ListParserResult parseList(String html, SourceConfig sourceConfig, int page) {
        return ListParserResult.fail("请使用 SztuCmsListParser 解析列表");
    }

    @Override
    public ContentParserResult parseContent(String html, SourceConfig sourceConfig, String itemId) {
        // 直接委托给公文通详情解析器
        return delegate.parseContent(html, sourceConfig, itemId);
    }
}