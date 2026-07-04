package top.naccl.entity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import java.util.Date;

/**
 * @Description: 博客动态
 * @Author: Naccl
 * @Date: 2020-08-24
 */
@NoArgsConstructor
@Getter
@Setter
@ToString
@ltm.lombok.NoArgsConstructor
@ltm.lombok.Getter
@ltm.lombok.Setter
@ltm.lombok.ToString
public class Moment {

    private Long id;

    //动态内容
    private String content;

    //创建时间
    private Date createTime;

    //点赞数量
    private Integer likes;

    //是否公开
    private Boolean published;
}
