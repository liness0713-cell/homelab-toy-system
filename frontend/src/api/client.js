const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || "http://localhost:8080";

const TOKEN_KEY = "toy-system-token";

export function getToken() {
  return localStorage.getItem(TOKEN_KEY);
}

export function setToken(token) {
  localStorage.setItem(TOKEN_KEY, token);
}

export function clearToken() {
  localStorage.removeItem(TOKEN_KEY);
}

async function request(path, options = {}) {
  const token = getToken();
  const headers = {
    "Content-Type": "application/json",
    ...options.headers,
  };
  if (token) {
    headers.Authorization = `Bearer ${token}`;
  }

  const response = await fetch(`${API_BASE_URL}${path}`, { ...options, headers });

  if (response.status === 401) {
    clearToken();
    throw new Error("未登录或登录已过期，请重新登录");
  }

  if (!response.ok) {
    const body = await response.json().catch(() => ({}));
    throw new Error(body.message || `请求失败: ${response.status}`);
  }

  if (response.status === 204) {
    return null;
  }
  return response.json();
}

export async function login(username, password) {
  const response = await fetch(`${API_BASE_URL}/auth/login`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ username, password }),
  });

  if (!response.ok) {
    throw new Error(response.status === 401 ? "用户名或密码错误" : `登录失败: ${response.status}`);
  }

  const data = await response.json();
  setToken(data.token);
  return data;
}

export function listPolicies() {
  return request("/api/policies");
}

export function createPolicy(payload) {
  return request("/api/policies", {
    method: "POST",
    body: JSON.stringify(payload),
  });
}

export function cancelPolicy(id) {
  return request(`/api/policies/${id}/cancel`, { method: "POST" });
}

export function searchPolicies(q) {
  // URLSearchParams负责把中文/日文这类多字节字符正确percent-encode，
  // 不然像"検索"这种原始UTF-8字符直接拼进URL，网关的Netty服务器会因为请求行不合法直接拒绝(400)。
  const params = new URLSearchParams();
  if (q) {
    params.set("q", q);
  }
  return request(`/api/search/policies?${params.toString()}`);
}
