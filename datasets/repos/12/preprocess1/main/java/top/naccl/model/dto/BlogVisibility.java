package top.naccl.model.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * @Description: 博客可见性DTO
 * @Author: Naccl
 * @Date: 2020-09-04
 */
@NoArgsConstructor
@Getter
@Setter
@ToString
@ltm.lombok.NoArgsConstructor
@ltm.lombok.Getter
@ltm.lombok.Setter
@ltm.lombok.ToString
public class BlogVisibility {

    //赞赏开关
    private Boolean appreciation;

    //推荐开关
    private Boolean recommend;

    //评论开关
    private Boolean commentEnabled;

    //是否置顶
    private Boolean top;

    //公开或私密
    private Boolean published;

    //密码保护
    private String password;
}
