package cn.edu.sztui.stream.infrastructure.util.stream;

/**
 * Redis Stream Key 常量定义
 *
 * Stream 命名规范: stream:{topic}
 * 消费者组命名规范: group:{topic}
 * 
 * 文件位置：module-stream/src/main/java/cn/edu/sztui/stream/infrastructure/util/stream/StreamKeys.java
 */
public final class StreamKeys {

    private StreamKeys() {}

    // ==================== Stream Keys ====================
    /** 课表更新流 */
    public static final String STREAM_SCHEDULE = "stream:schedule";
    /** 公告更新流 */
    public static final String STREAM_ANNOUNCEMENT = "stream:announcement";
    /** 日历/活动更新流 */
    public static final String STREAM_CALENDAR = "stream:calendar";

    // ==================== Consumer Groups ====================
    /** 课表消费者组 */
    public static final String GROUP_SCHEDULE = "group:schedule";
    /** 公告消费者组 */
    public static final String GROUP_ANNOUNCEMENT = "group:announcement";
    /** 日历消费者组 */
    public static final String GROUP_CALENDAR = "group:calendar";
    /** SSE 推送消费者组（用于实时推送） */
    public static final String GROUP_SSE_PUSH = "sse-push-group";

    // ==================== Message Fields ====================
    /** 消息类型字段 */
    public static final String FIELD_TYPE = "type";
    /** 消息数据字段 (JSON) */
    public static final String FIELD_DATA = "data";
    /** 目标用户字段 (null 表示广播) */
    public static final String FIELD_TARGET_USER = "targetUser";
    /** 时间戳字段 */
    public static final String FIELD_TIMESTAMP = "timestamp";

    // ==================== Message Types ====================
    // 课表相关
    /** 课表数据推送 */
    public static final String TYPE_SCHEDULE_DATA = "SCHEDULE_DATA";
    /** 课表更新通知 */
    public static final String TYPE_SCHEDULE_UPDATE = "SCHEDULE_UPDATE";

    // 公告相关
    /** 公告数据推送 */
    public static final String TYPE_ANNOUNCEMENT_DATA = "ANNOUNCEMENT_DATA";
    /** 新公告通知 */
    public static final String TYPE_NEW_ANNOUNCEMENTS = "NEW_ANNOUNCEMENTS";
    /** 公告系统状态 */
    public static final String TYPE_ANNOUNCEMENT_STATUS = "ANNOUNCEMENT_STATUS";

    // 日历相关
    /** 日历数据推送 */
    public static final String TYPE_CALENDAR_DATA = "CALENDAR_DATA";
    /** 日历更新通知 */
    public static final String TYPE_CALENDAR_UPDATE = "CALENDAR_UPDATE";

    // 通用/系统
    /** 需要重新登录 */
    public static final String TYPE_AUTH_REQUIRED = "AUTH_REQUIRED";
    /** 心跳消息 */
    public static final String TYPE_HEARTBEAT = "HEARTBEAT";
    /** 连接成功响应 */
    public static final String TYPE_CONNECTED = "CONNECTED";
    /** 错误消息 */
    public static final String TYPE_ERROR = "ERROR";
}
