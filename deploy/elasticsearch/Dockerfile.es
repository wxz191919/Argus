# ---- ES 8.14.3 + IK 中文分词器 ----
# Argus 索引模板依赖 IK 分词（ik_max_word/ik_smart），官方镜像不含该插件，需自建。
# 构建在 docker-compose.yml 中通过 build 段自动执行。
FROM docker.elastic.co/elasticsearch/elasticsearch:8.14.3

# 安装 IK 分词器（infinilabs 官方发布站，版本必须与 ES 完全一致）
RUN bin/elasticsearch-plugin install --batch \
  https://release.infinilabs.com/analysis-ik/stable/elasticsearch-analysis-ik-8.14.3.zip
