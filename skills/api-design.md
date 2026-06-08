---
name: api-design
description: 当用户需要设计RESTful API或审查API设计时使用
version: "1.0.0"
tools:
  - web_search
parameters:
  style: restful
  versioning: url
---

# API 设计规范

## 设计原则

1. **RESTful 风格**：资源导向、HTTP方法语义化
2. **一致性**：命名、格式、错误处理统一
3. **可演进**：版本控制、向后兼容
4. **安全性**：认证、授权、限流

## URL 设计

### 命名规范
- 使用名词复数：`/users`、`/orders`
- 小写字母 + 连字符：`/user-profiles`
- 层级关系：`/users/{id}/orders`
- 避免动词：用HTTP方法表示操作

### 版本控制
```
/api/v1/users
/api/v2/users
```

## HTTP 方法

| 方法 | 用途 | 幂等 | 安全 |
|------|------|------|------|
| GET | 获取资源 | ✓ | ✓ |
| POST | 创建资源 | ✗ | ✗ |
| PUT | 全量更新 | ✓ | ✗ |
| PATCH | 部分更新 | ✓ | ✗ |
| DELETE | 删除资源 | ✓ | ✗ |

## 响应格式

### 成功响应
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "id": 1,
    "name": "example"
  }
}
```

### 列表响应
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "items": [...],
    "total": 100,
    "page": 1,
    "pageSize": 20
  }
}
```

### 错误响应
```json
{
  "code": 400,
  "message": "Validation failed",
  "errors": [
    {"field": "email", "message": "Invalid format"}
  ]
}
```

## 状态码使用

| 状态码 | 含义 | 使用场景 |
|--------|------|----------|
| 200 | OK | 成功 |
| 201 | Created | 创建成功 |
| 204 | No Content | 删除成功 |
| 400 | Bad Request | 参数错误 |
| 401 | Unauthorized | 未认证 |
| 403 | Forbidden | 无权限 |
| 404 | Not Found | 资源不存在 |
| 422 | Unprocessable Entity | 业务逻辑错误 |
| 429 | Too Many Requests | 限流 |
| 500 | Internal Server Error | 服务器错误 |

## 认证方案

### JWT Token
```
Authorization: Bearer <token>
```

### API Key
```
X-API-Key: <key>
```

## 分页、过滤、排序

### 分页
```
GET /api/v1/users?page=1&pageSize=20
```

### 过滤
```
GET /api/v1/users?status=active&role=admin
```

### 排序
```
GET /api/v1/users?sort=created_at:desc
```

## 文档规范

- 使用 OpenAPI 3.0 (Swagger)
- 每个接口包含：描述、参数、响应、示例
- 提供在线测试功能
- 版本变更日志
