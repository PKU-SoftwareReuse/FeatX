package cn.edu.pku.lixutian.dto.result;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CodeFileDiffResult {
    private String key;
    private String path;
    private String language;
    private String originalContent;
    private String modifiedContent;
    private String diff;
    private boolean newFile;
    private boolean deleted;
    private boolean editable;
    private boolean staged;
    private String stagedContent;
    private String warning;
}
