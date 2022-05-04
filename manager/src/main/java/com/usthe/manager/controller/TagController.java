package com.usthe.manager.controller;

import com.usthe.common.entity.dto.Message;
import com.usthe.common.entity.manager.Tag;
import com.usthe.manager.service.TagService;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.Predicate;
import javax.validation.Valid;

import java.util.ArrayList;

import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;

import static org.springframework.http.MediaType.APPLICATION_JSON_VALUE;

/**
 * Tags management API
 * 标签管理API
 *
 *
 *
 */
@Api(tags = "Tag Manage API | 标签管理API")
@RestController
@RequestMapping(path = "/api/tag", produces = {APPLICATION_JSON_VALUE})
public class TagController {

    @Autowired
    private TagService tagService;

    @PostMapping
    @ApiOperation(value = "Add Tag", notes = "新增监控标签")
    public ResponseEntity<Message<Void>> addNewTags(@Valid @RequestBody List<Tag> tags) {
        // Verify request data  校验请求数据 去重
        tags = tags.stream().peek(tag -> {
            tag.setType((byte) 1);
            tag.setId(null);
            }).filter(tag -> tag.getValue() != null)
            .distinct().collect(Collectors.toList());
        tagService.addTags(tags);
        return ResponseEntity.ok(new Message<>("Add success"));
    }

    @PutMapping
    @ApiOperation(value = "Modify an existing tag", notes = "修改一个已存在标签")
    public ResponseEntity<Message<Void>> modifyMonitor(@Valid @RequestBody Tag tag) {
        // Verify request data  校验请求数据
        if (tag.getId() == null || tag.getName() == null) {
            throw new IllegalArgumentException("The Tag not exist.");
        }
        tagService.modifyTag(tag);
        return ResponseEntity.ok(new Message<>("Modify success"));
    }

    @GetMapping()
    @ApiOperation(value = "Get tags information", notes = "根据条件获取标签信息")
    public ResponseEntity<Message<Page<Tag>>> getTags(
            @ApiParam(value = "Tag content search | 标签内容模糊查询", example = "status") @RequestParam(required = false) String search,
            @ApiParam(value = "Tag type | 标签类型", example = "0") @RequestParam(required = false) Byte type,
            @ApiParam(value = "List current page | 列表当前分页", example = "0") @RequestParam(defaultValue = "0") int pageIndex,
            @ApiParam(value = "Number of list pagination | 列表分页数量", example = "8") @RequestParam(defaultValue = "8") int pageSize) {
        // Get tag information
        Specification<Tag> specification = (root, query, criteriaBuilder) -> {
            List<Predicate> andList = new ArrayList<>();
            if (type != null) {
                Predicate predicateApp = criteriaBuilder.equal(root.get("type"), type);
                andList.add(predicateApp);
            }
            Predicate[] andPredicates = new Predicate[andList.size()];
            Predicate andPredicate = criteriaBuilder.and(andList.toArray(andPredicates));

            List<Predicate> orList = new ArrayList<>();
            if (search != null && !"".equals(search)) {
                Predicate predicateName = criteriaBuilder.like(root.get("name"), "%" + search + "%");
                orList.add(predicateName);
                Predicate predicateValue = criteriaBuilder.like(root.get("value"), "%" + search + "%");
                orList.add(predicateValue);
            }
            Predicate[] orPredicates = new Predicate[orList.size()];
            Predicate orPredicate = criteriaBuilder.or(orList.toArray(orPredicates));

            if (andPredicate.getExpressions().isEmpty() && orPredicate.getExpressions().isEmpty()) {
                return query.where().getRestriction();
            } else if (andPredicate.getExpressions().isEmpty()) {
                return query.where(orPredicate).getRestriction();
            } else if (orPredicate.getExpressions().isEmpty()) {
                return query.where(andPredicate).getRestriction();
            } else {
                return query.where(andPredicate, orPredicate).getRestriction();
            }
        };
        PageRequest pageRequest = PageRequest.of(pageIndex, pageSize);
        Page<Tag> alertPage = tagService.getTags(specification, pageRequest);
        Message<Page<Tag>> message = new Message<>(alertPage);
        return ResponseEntity.ok(message);
    }

    @DeleteMapping()
    @ApiOperation(value = "Delete tags based on ID", notes = "根据TAG ID删除TAG")
    public ResponseEntity<Message<Void>> deleteTags(
            @ApiParam(value = "TAG IDs | 监控ID列表", example = "6565463543") @RequestParam(required = false) List<Long> ids) {
        if (ids != null && !ids.isEmpty()) {
            tagService.deleteTags(new HashSet<>(ids));
        }
        return ResponseEntity.ok(new Message<>("Delete success"));
    }
}
