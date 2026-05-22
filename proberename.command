#!/bin/bash

# 1. 锁定脚本所在的绝对路径
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR" || { echo "无法切换到目录: $SCRIPT_DIR"; exit 1; }

echo "--- 任务开始 ---"
echo "正在处理目录: $SCRIPT_DIR"

# 2. 使用 find 查找当前层级下的 .mp4 文件
# -maxdepth 1 表示只看当前目录，不进子目录（如需递归，删掉这行）
# -print0 配合 IFS= read -r -d '' 是处理空格文件的“黄金标准”
find . -maxdepth 1 -name "*.mp4" -type f -print0 | while IFS= read -r -d '' f; do
    
    # 去掉路径前缀 ./ 只保留纯文件名
    file="${f#./}"
    
    # 3. 使用 ffprobe 获取视频时长（秒）
    # 2>/dev/null 屏蔽非关键错误输出
    duration_raw=$(ffprobe -v error -show_entries format=duration -of csv=p=0 "$file" 2>/dev/null)

    # 检查是否成功获取到时长且不为 N/A
    if [[ -n "$duration_raw" && "$duration_raw" != "N/A" ]]; then
        # 去掉小数点部分，取整
        d_int=${duration_raw%.*}
        
        # 计算 时:分:秒
        h=$((d_int / 3600))
        m=$(( (d_int % 3600) / 60 ))
        s=$((d_int % 60))
        
        # 格式化前缀，例如 01-15-30
        prefix=$(printf "%02d-%02d-%02d" $h $m $s)
        
        # 4. 防止重复命名的逻辑
        # 如果文件名已经以该时间格式开头，则跳过
        if [[ "$file" =~ ^[0-9]{2}-[0-9]{2}-[0-9]{2}_ ]]; then
            echo " [跳过] 已处理过: $file"
            continue
        fi

        newname="${prefix}_$file"
        
        # 5. 执行重命名
        # -n 参数代表 no-clobber，如果目标文件已存在则不覆盖
        if mv -n "$file" "$newname" 2>/dev/null; then
            echo " [成功] $file -> $newname"
        else
            echo " [失败] 无法重命名 $file (可能目标已存在)"
        fi
    else
        echo " [警告] 无法读取时长: $file"
    fi
done

echo "--- 任务完成 ---"

