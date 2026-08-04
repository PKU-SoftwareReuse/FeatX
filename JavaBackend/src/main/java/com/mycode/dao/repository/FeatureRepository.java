package com.mycode.dao.repository;

import com.mycode.dao.Feature;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface FeatureRepository extends JpaRepository<Feature, Integer> {
    List<Feature> findByModule_Id(Integer moduleId);
    void deleteById(Integer featureId);
}
