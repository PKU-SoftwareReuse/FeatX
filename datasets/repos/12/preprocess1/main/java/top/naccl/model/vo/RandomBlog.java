package top.naccl.model.vo;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import java.util.Date;

/**
 * @Description: 随机博客
 * @Author: Naccl
 * @Date: 2020-08-17
 */
@NoArgsConstructor
@Getter
@Setter
@ToString
@ltm.lombok.NoArgsConstructor
@ltm.lombok.Getter
@ltm.lombok.Setter
@ltm.lombok.ToString
public class RandomBlog {

    private Long id;

    //文章标题
    private String title;

    //文章首图，用于随机文章展示
    private String firstPicture;

    //创建时间
    private Date createTime;

    //文章密码
    private String password;

    //是否私密文章
    private Boolean privacy;
}
