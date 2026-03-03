package cn.edu.sztui.base.application.vo;

import lombok.Data;

/**
 * 附件 VO
 */
@Data
public class AttachmentVo {
    /** 文件名 */
    private String name;
    /** 下载链接 */
    private String url;
    /** 文件类型：pdf/word/excel/ppt/archive/file */
    private String type;
}
