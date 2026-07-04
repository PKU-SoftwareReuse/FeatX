package top.naccl.entity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import java.util.ArrayList;
import java.util.List;

/**
 * @Description: 博客分类
 * @Author: Naccl
 * @Date: 2020-07-26
 */
@NoArgsConstructor
@Getter
@Setter
@ToString
@ltm.lombok.NoArgsConstructor
@ltm.lombok.Getter
@ltm.lombok.Setter
@ltm.lombok.ToString
public class Category {

    private Long id;

    //分类名称
    private String name;

    //该分类下的博客文章
    private List<Blog> blogs = new ArrayList<>();
}
