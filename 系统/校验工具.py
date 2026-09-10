"""标准库机械校验；不连接GitHub，不授权定稿，不判断文学质量。"""
import argparse
import hashlib
import json
import re
from pathlib import Path, PurePosixPath

PROJECT = 'XIAOSHUO3'
SCORE_WEIGHTS = {'任务契合': 15, '因果结构': 25, '人物塑造': 20,
                 '阅读节奏': 15, '中文表达': 20, '特色与完成度': 5}
UNITS = re.compile(r'[\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff\U00020000-\U0002ebef\U00030000-\U000323af]|[A-Za-z0-9]+')

def count_words(text):
    return len(UNITS.findall(text))

def blob_sha(text):
    data = text.encode('utf-8')
    return hashlib.sha1(b'blob '+str(len(data)).encode()+b'\0'+data).hexdigest()

def require(condition, message):
    if not condition:
        raise ValueError(message)

def validate_path(path):
    require(isinstance(path, str) and bool(path), '路径为空')
    p = PurePosixPath(path)
    require(not p.is_absolute() and '..' not in p.parts and '\\' not in path,
            '路径越界或格式错误')
    return path

def weighted_score(scores):
    require(set(scores) == set(SCORE_WEIGHTS), '评分项目不完整')
    require(all(isinstance(v, (int, float)) and not isinstance(v, bool) and 0 <= v <= 10
                for v in scores.values()), '单项分数无效')
    return round(sum(scores[k]*v/10 for k, v in SCORE_WEIGHTS.items()), 1)

def score_passes(scores):
    return (weighted_score(scores) >= 80 and
            all(scores[k] >= 7 for k in ('任务契合', '因果结构', '人物塑造')) and
            scores['中文表达'] >= 8)

def validate_writer_receipt(receipt, task, state, text):
    """调用者须提供真实读取的正文、任务、状态；本函数不证明来源真实。"""
    require(all(x.get('项目代号') == PROJECT for x in (receipt, task, state)), '项目不匹配')
    require(state.get('状态') == '执行中', '当前不可执行')
    active = state.get('当前任务') or {}
    require(receipt.get('任务编号') == task.get('任务编号') == active.get('编号'), '旧任务或错任务')
    require(receipt.get('任务提交') == active.get('提交') and bool(active.get('提交')), '任务提交不匹配')
    require(receipt.get('资料提交') == task.get('资料提交') and bool(task.get('资料提交')), '资料提交不匹配')
    require(receipt.get('章号') == task.get('章号') == state.get('当前章'), '章号不匹配')
    role = receipt.get('角色')
    require(role in ('写手A', '写手B', '写手C') and role in task.get('执行角色', []), '执行角色不匹配')
    require(receipt.get('交付状态') == '可交接', '产物未完成')
    body = receipt.get('正文') or {}
    allowed = task.get('允许产物', {}).get(role, [])
    path = validate_path(body.get('路径'))
    require(path in allowed, '正文路径未获授权')
    require(body.get('文件SHA') == blob_sha(text), '正文文件SHA不匹配')
    require(bool(re.fullmatch(r'[0-9a-f]{40}', body.get('提交') or '')), '正文提交无效')
    n = count_words(text)
    require(8000 <= n <= 10000, '字数不合格')
    require(type(body.get('字数')) is int and body['字数'] == n, '计数字数不匹配')
    require(bool(body.get('计数证据')), '缺少计数证据')
    records = receipt.get('读取记录', [])
    for src in task.get('必读来源', []):
        require(any(r.get('路径') == src['路径'] and
                    r.get('提交') == task['资料提交'] and
                    r.get('文件SHA') == src['文件SHA'] and
                    r.get('范围') == src['范围'] and bool(r.get('实际发现'))
                    for r in records), '缺少必读覆盖或版本不匹配：'+src['路径'])
    return {'字数': n, '正文文件SHA': blob_sha(text), '机械检查': '通过'}

def validate_lock(receipt, review, final_text):
    """验证同稿与声明字段；不能代替总监阅读、真实回执和最后发布。"""
    body = receipt.get('正文') or {}
    require(receipt.get('项目代号') == review.get('项目代号') == PROJECT, '审核项目不匹配')
    for field in ('任务编号', '任务提交', '资料提交', '章号'):
        require(bool(receipt.get(field)) and review.get(field) == receipt.get(field), '审核任务绑定不匹配：'+field)
    require(review.get('审核结论') == '通过', '审核未通过')
    require(review.get('致命') == 0 and review.get('重要') == 0, '仍有阻断问题')
    require(review.get('全文覆盖') is True, '全文覆盖不足')
    require(review.get('事实申报通过') is True and review.get('阶段条件通过') is True, '记忆或阶段检查未通过')
    require(score_passes(review.get('评分', {})), '评分门槛未达到')
    sha = blob_sha(final_text)
    require(body.get('文件SHA') == review.get('正文文件SHA') == sha, '审核与定稿不是同一正文')
    require(review.get('正文提交') == body.get('提交'), '审核的正文提交不匹配')
    require(review.get('正文路径') == body.get('路径'), '审核的正文路径不匹配')
    n = count_words(final_text)
    require(8000 <= n <= 10000 and body.get('字数') == n, '定稿字数无效')
    return {'机械检查': '通过', '字数': n, '正文文件SHA': sha}

def validate_corpus(entries):
    require(len(entries) == 100, '不是100份有效定稿')
    require({x['章号'] for x in entries} == set(range(1, 101)), '缺章或重复章')
    for x in entries:
        require(type(x['字数']) is int and 8000 <= x['字数'] <= 10000, '存在字数不合格章节')
        require(bool(re.fullmatch(r'[0-9a-f]{40}', x['正文文件SHA'] or '')) and x['正文文件SHA'] == x['审核正文文件SHA'], '存在审核版本失配')
        require(bool(re.fullmatch(r'[0-9a-f]{40}', x['锁定凭证提交'] or '')), '缺少已发布锁定凭证')
    return {'机械检查': '通过', '章数': 100, '总字数': sum(x['字数'] for x in entries)}

if __name__ == '__main__':
    p = argparse.ArgumentParser(description='给能执行代码的AI使用，用户无需运行')
    p.add_argument('file', help='只含小说正文的UTF-8 TXT')
    a = p.parse_args()
    text = Path(a.file).read_bytes().decode('utf-8')
    n = count_words(text)
    print(json.dumps({'字数': n, '字数合格': 8000 <= n <= 10000,
                      '正文文件SHA': blob_sha(text),
                      'SHA256': hashlib.sha256(text.encode('utf-8')).hexdigest(),
                      '口径': '汉字逐字，连续英文数字串各一词；不计标点和空白'}, ensure_ascii=False))
