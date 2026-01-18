package cn.edu.pku.lixutian.dto.request;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class FeatureSelectRequest {
    private List<Integer> features;
}
