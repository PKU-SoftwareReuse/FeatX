package top.naccl.entity;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

/**
 * @Description: 城市访客数量
 * @Author: Naccl
 * @Date: 2021-02-26
 */
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
@ToString
@ltm.lombok.AllArgsConstructor
@ltm.lombok.NoArgsConstructor
@ltm.lombok.Getter
@ltm.lombok.Setter
@ltm.lombok.ToString
public class CityVisitor {

    //城市名称
    private String city;

    //独立访客数量
    private Integer uv;
}
