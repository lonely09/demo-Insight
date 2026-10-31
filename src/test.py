# 生成一个约 64K tokens 的测试文本文件 (按英文 1 token ≈ 4 字符算)
def generate_64k_text(filename="test_64k.txt"):
    target_chars = 64000 * 4  # 约 256,000 个字符
    words = ["The quick brown fox jumps over the lazy dog. ", "Data analysis requires clear context. "]
    
    current_len = 0
    content = []
    
    # 第一句加入测试指令
    content.append("【系统指令】：请仔细阅读以下这篇长文，读完后只在结尾回复我：“我已成功阅读 64K 上下文”。\n\n【长文开始】\n")
    current_len += len(content[0])
    
    # 循环填充直到接近目标字符数
    i = 0
    while current_len < target_chars:
        sentence = words[i % len(words)]
        content.append(sentence)
        current_len += len(sentence)
        i += 1
        
    content.append("\n【长文结束】\n请回复：我已成功阅读 64K 上下文。")
    
    with open(filename, "w", encoding="utf-8") as f:
        f.writelines(content)
        
    print(f"✅ 生成成功！文件大小约为 {current_len} 字符，保存在当前目录下的 {filename}")

if __name__ == "__main__":
    generate_64k_text()