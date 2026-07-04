package top.naccl.model.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * @Description: 友链DTO
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
}
