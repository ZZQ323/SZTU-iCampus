package cn.edu.sztui.base.infrastructure.stream;

/**
 * Redis Stream Key 常量定义
 * 
 * Stream 命名规范: stream:{topic}
 * 消费者组命名规范: group:{topic}
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
    
    /** 课表数据推送 */
    public static final String TYPE_SCHEDULE_DATA = "SCHEDULE_DATA";
    
    /** 需要重新登录 */
    public static final String TYPE_AUTH_REQUIRED = "AUTH_REQUIRED";
    
    /** 公告数据推送 */
    public static final String TYPE_ANNOUNCEMENT_DATA = "ANNOUNCEMENT_DATA";
    
    /** 心跳消息 */
    public static final String TYPE_HEARTBEAT = "HEARTBEAT";
}
