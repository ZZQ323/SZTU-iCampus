package cn.edu.sztui.stream.application.service;

import cn.edu.sztui.stream.infrastructure.persistence.entity.textDTO.AnnouncementContentVo;
import cn.edu.sztui.stream.infrastructure.persistence.entity.textDTO.AnnouncementListVo;
import cn.edu.sztui.stream.infrastructure.persistence.entity.textDTO.AnnouncementMetaVo;

import java.util.List;

/**
 * 公告服务接口
 * 
 * 文件位置：module-stream/src/main/java/cn/edu/sztui/stream/application/service/AnnouncementService.java
 */
public interface AnnouncementService {

    /**
     * 获取公告列表
     * 
     * @param category 分类代码（可选）
     * @param page 页码
     * @param pageSize 每页数量
     * @return 列表结果
     */
    AnnouncementListVo getList(String category, int page, int pageSize);

    /**
     * 获取增量公告
     * 
     * @param lastId 上次已读的最新 ID
     * @return 增量列表
     */
    List<AnnouncementMetaVo> getIncremental(String lastId);

    /**
     * 获取公告详情
     * 
     * @param wxOpenId 用户 OpenId（用于获取 Cookie）
     * @param id 公告 ID
     * @return 详情内容
     */
    AnnouncementContentVo getDetail(String wxOpenId, String id);

    /**
     * 标题搜索
     * 
     * @param keyword 关键词
     * @param limit 最大返回数量
     * @return 搜索结果
     */
    List<AnnouncementMetaVo> searchByTitle(String keyword, int limit);

    /**
     * 全文搜索（代理学校接口）
     * 
     * @param wxOpenId 用户 OpenId
     * @param keyword 关键词
     * @param scope 搜索范围：1=全部, 2=标题, 3=正文
     * @param category 分类代码
     * @param page 页码
     * @return 搜索结果
     */
    AnnouncementListVo fullTextSearch(String wxOpenId, String keyword, Integer scope, String category, Integer page);

    /**
     * 增量爬取
     * 
     * @param wxOpenId Cookie 来源用户
     * @return 新增的公告列表
     */
    List<AnnouncementMetaVo> crawlIncremental(String wxOpenId);

    /**
     * 全量初始化
     * 
     * @param wxOpenId Cookie 来源用户
     * @return 爬取的公告数量
     */
    int initialize(String wxOpenId);

    /**
     * 获取总页数（从学校网站）
     * 
     * @param wxOpenId Cookie 来源用户
     * @return 总页数
     */
    int getTotalPage(String wxOpenId);

    /**
     * 爬取指定页的公告列表
     * 
     * @param wxOpenId Cookie 来源用户
     * @param page 页码
     * @return 公告列表
     */
    List<AnnouncementMetaVo> crawlPage(String wxOpenId, int page);

    /**
     * 预爬取详情（热点预热）
     * 
     * @param wxOpenId Cookie 来源用户
     * @param ids 要预爬取的 ID 列表
     */
    void preCrawlDetails(String wxOpenId, List<String> ids);
}
