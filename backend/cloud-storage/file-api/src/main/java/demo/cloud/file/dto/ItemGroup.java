package demo.cloud.file.dto;

import demo.cloud.file.constant.FileItemType;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public record ItemGroup(List<Long> fileIds, List<Long> folderIds) {

    // 1. 静态工厂方法：核心转换逻辑
    public static ItemGroup from(Collection<ItemIdentity> items) {
        if (items == null || items.isEmpty()) {
            return empty();
        }

        // 一次分组，高性能
        Map<FileItemType, List<Long>> map = items.stream()
                .collect(Collectors.groupingBy(
                        ItemIdentity::getType,
                        Collectors.mapping(ItemIdentity::getId, Collectors.toList())
                ));

        // 注意：返回不可变列表更安全（防止业务代码误增删）
        List<Long> fileIds = map.getOrDefault(FileItemType.FILE, Collections.emptyList());
        List<Long> folderIds = map.getOrDefault(FileItemType.FOLDER, Collections.emptyList());

        return new ItemGroup(fileIds, folderIds);
    }

    // 2. 空对象工厂（用于返回空结果，避免 null）
    public static ItemGroup empty() {
        return new ItemGroup(Collections.emptyList(), Collections.emptyList());
    }

    // 3. 辅助方法：判空
    public boolean isEmpty() {
        return fileIds.isEmpty() && folderIds.isEmpty();
    }

    // 4. 辅助方法：总数量
    public int total() {
        return fileIds.size() + folderIds.size();
    }
}