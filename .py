from PIL import Image

def remove_white_background(input_path, output_path):
    # 打开图片并转换为 RGBA 模式
    img = Image.open(input_path).convert("RGBA")
    datas = img.getdata()
    
    newData = []
    # 容差值：如果 RGB 都大于 235，就认为是背景（把 235 改小可以去除更多灰白毛边）
    threshold = 200
    
    for item in datas:
        # item 是 (R, G, B, A)
        if item[0] > threshold and item[1] > threshold and item[2] > threshold:
            # 替换为透明
            newData.append((255, 255, 255, 0))
        else:
            newData.append(item)
            
    img.putdata(newData)
    img.save(output_path, "PNG")
    print(f"处理完成，已保存至: {output_path}")

# 执行处理
# 注意：这里的文件名要和左侧文件树里的名字对应
remove_white_background(r"L:\lgm\MyGradleApp\app\src\main\resources\img\download_img.png", "output.png")