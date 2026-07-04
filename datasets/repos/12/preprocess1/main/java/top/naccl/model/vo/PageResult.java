package top.naccl.model.vo;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import java.util.List;

/**
 * @Description: 分页结果
 * @Author: Naccl
 * @Date: 2020-08-08
 */
@NoArgsConstructor
@Getter
@Setter
@ToString
@ltm.lombok.NoArgsConstructor
@ltm.lombok.Getter
@ltm.lombok.Setter
@ltm.lombok.ToString
public class PageResult<T> {

    //总页数
    private Integer totalPage;

    //数据
    private List<T> list;

    public PageResult(Integer totalPage, List<T> list) {
        this.totalPage = totalPage;
        this.list = list;
    }
}
