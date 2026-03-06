package cn.edu.sztui.base.application.service;

import cn.edu.sztui.base.application.vo.AnnouncementContentVo;
import cn.edu.sztui.base.application.vo.AnnouncementListVo;
import cn.edu.sztui.base.application.vo.AnnouncementMetaVo;
import java.util.List;

/**
 * 公告服务接口
 */

public interface AnnouncementService {

    /**
     * 获取公告列表
     *
     * @param category 分类代码（可选）：1018/1019/1020/1021/1022
     * @param page 页码，从1开始
     * @param pageSize 每页数量
     * @return 公告列表响应
     */
    AnnouncementListVo getList(String category, Integer page, Integer pageSize);

    /**
     * 获取公告详情
     *
     * @param openId 用户 openId（用于获取 Cookie）
     * @param id 公告 ID
     * @return 公告详情内容
     */
    AnnouncementContentVo getDetail(String openId, String id);

    /**
     * 获取增量公告（用于检查新公告）
     *
     * @param lastId 上次已读的最新ID
     * @return 新公告列表
     */
    List<AnnouncementMetaVo> getIncremental(String lastId);

    /**
     * 标题搜索
     *
     * @param keyword 搜索关键词
     * @param limit 最大返回数量
     * @return 匹配的公告列表
     */
    List<AnnouncementMetaVo> searchByTitle(String keyword, int limit);

    /**
     * 全文搜索（代理学校接口）
     *
     * @param openId 用户 openId
     * @param keyword 搜索关键词
     * @param scope 搜索范围（1=标题, 2=全文）
     * @param category 分类代码（可选）
     * @param page 页码
     * @return 搜索结果
     */
    AnnouncementListVo fullTextSearch(String openId, String keyword, Integer scope,
                                      String category, Integer page);

    /**
     * 增量爬取（定时任务调用）
     *
     * @param sourceOpenId Cookie 来源的 openId
     * @return 新公告列表
     */
    List<AnnouncementMetaVo> crawlIncremental(String sourceOpenId);

    /**
     * 爬取公告列表页
     *
     * @param openId 用户 openId
     * @param page 页码
     * @return 公告元数据列表
     */
    List<AnnouncementMetaVo> crawlAnnouncementList(String openId, int page);
}