package cn.edu.pku.lixutian.service;

import cn.edu.pku.lixutian.graph.SKG;
import cn.edu.pku.lixutian.helper.PreprocessHelper;
import com.github.javaparser.ParseException;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.NodeList;
import org.springframework.stereotype.Service;

import java.io.*;


@Service
public class ProcessService {

    public void process() throws ParseException, IOException, InterruptedException {

        System.out.println("=-=-=-=-=-=-=-= Parse All Files =-=-=-=-=-=-=-=");
        NodeList<CompilationUnit> units = PreprocessHelper.parseAllFiles();

        System.out.println("=-=-=-=-=-=-=-= Build All Graph =-=-=-=-=-=-=-=");
        SKG skg = SKG.getNewInstance();
        skg.build(units);
        skg.cluster();
    }


}
