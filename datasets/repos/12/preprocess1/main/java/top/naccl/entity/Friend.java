package top.naccl.entity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import java.util.Date;

/**
 * @Description: 友链
 * @Author: Naccl
 * @Date: 2020-09-08
 */
@NoArgsConstructor
@Getter
@Setter
@ToString
@ltm.lombok.NoArgsConstructor
@ltm.lombok.Getter
@ltm.lombok.Setter
@ltm.lombok.ToString
public class Friend {

    private Long id;

    //昵称
    private String nickname;

    //描述
    private String description;

    //站点
    private String website;

    //头像
    private String avatar;

    //公开或隐藏
    private Boolean published;

    //浏览次数
    private Integer views;

    //创建时间
    private Date createTime;
}
