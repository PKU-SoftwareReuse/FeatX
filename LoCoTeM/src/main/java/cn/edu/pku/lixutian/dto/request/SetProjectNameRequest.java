package cn.edu.pku.lixutian.dto.request;

import cn.edu.pku.lixutian.dto.result.ProjectInfoResult;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SetProjectNameRequest {
    private ProjectInfoResult projectInfoResult;

    private Options options;

    @Getter
    @Setter
    public static class Options {
        private boolean option1;
        private boolean option2;
        private boolean option3;
    }
}
