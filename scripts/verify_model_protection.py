"""使用临时、不持久化 Redis 验证保护脚本，不连接业务存储。需安装 redis-server 和 redis-cli。"""
import concurrent.futures
import pathlib
import subprocess
import tempfile
import time

ROOT = pathlib.Path(__file__).resolve().parents[1]


def verify(socket):
    def command(*args):
        return subprocess.check_output(
            ["redis-cli", "-s", socket, "--raw", *map(str, args)], text=True
        ).strip()

    def keys(model="m"):
        prefix = "gateway:{protection}:"
        return [
            prefix + model + ":state", prefix + model + ":probe",
            prefix + "global:inflight", prefix + model + ":inflight",
            prefix + "global:rpm", prefix + model + ":rpm",
            prefix + model + ":calls", prefix + model + ":failures",
            prefix + "p:inflight", prefix + "p:rpm",
        ]

    def run(name, model_keys, *args):
        return int(command(
            "--eval", ROOT / f"src/main/resources/protection/{name}.lua",
            *model_keys, ",", *args,
        ))

    model = keys()
    assert run("acquire", model, "a", 3000, 1, 10, 100, 100, 0, 0) == 0
    assert run("acquire", keys("other"), "b", 3000, 1, 10, 100, 100, 0, 0) == 429
    assert run("renew", model, "a", 3000) == 1
    run("release", model, "a")
    assert run("acquire", model, "b", 3000, 1, 10, 100, 100, 0, 0) == 0
    assert run("result", model, "b", 1, 60000, 20, 50, 1, 100, 0) == 1
    run("release", model, "b")
    assert run("acquire", model, "c", 3000, 10, 10, 100, 100, 0, 0) == 503
    time.sleep(0.15)
    assert run("acquire", model, "probe", 3000, 10, 10, 100, 100, 0, 0) == 1
    assert run("acquire", model, "probe2", 3000, 10, 10, 100, 100, 0, 0) == 503
    assert run("result", model, "stale", 0, 60000, 20, 50, 5, 100, 1) == 0
    assert run("result", model, "probe", 0, 60000, 20, 50, 5, 100, 1) == 2
    run("release", model, "probe")
    assert run("acquire", model, "healthy", 3000, 10, 10, 100, 100, 0, 0) == 0
    run("release", model, "healthy")

    # FLUSHDB 只作用于本脚本创建的隔离进程，不使用外部传入的连接信息。
    command("FLUSHDB")
    assert run("acquire", model, "rpm1", 3000, 10, 10, 1, 100, 0, 0) == 0
    run("release", model, "rpm1")
    assert run("acquire", keys("other"), "rpm2", 3000, 10, 10, 1, 100, 0, 0) == 429
    command("FLUSHDB")

    def compete(number):
        return run("acquire", model, number, 3000, 3, 3, 100, 100, 0, 0)

    with concurrent.futures.ThreadPoolExecutor(max_workers=10) as pool:
        results = list(pool.map(compete, range(20)))
    assert results.count(0) == 3 and results.count(429) == 17, results

    # 模拟租约过期，确认回收并阻止旧调用续租。
    for key in (model[2], model[3], model[8]):
        for member in command("ZRANGE", key, 0, -1).splitlines():
            command("ZADD", key, 1, member)
    assert run("acquire", model, "replacement", 3000, 3, 3, 100, 100, 0, 0) == 0
    assert run("renew", model, "0", 3000) == 0
    command("FLUSHDB")
    assert run("acquire", model, "quota", 3000, 10, 10, 100, 100, 1, 100) == 0
    assert run("acquire", keys("other"), "quota2", 3000, 10, 10, 100, 100, 1, 100) == 429
    print("隔离 Redis 验证通过：共享额度、并发竞争、熔断、半开恢复、过期租约与迟到结果。")


if __name__ == "__main__":
    with tempfile.TemporaryDirectory(prefix="gateway-protection-") as temporary:
        socket = temporary + "/redis.sock"
        server = subprocess.Popen(
            ["redis-server", "--port", "0", "--unixsocket", socket,
             "--save", "", "--appendonly", "no", "--dir", temporary],
            stdout=subprocess.DEVNULL, stderr=subprocess.PIPE,
        )
        try:
            for _ in range(100):
                if pathlib.Path(socket).exists():
                    break
                if server.poll() is not None:
                    raise RuntimeError(server.stderr.read().decode())
                time.sleep(0.02)
            verify(socket)
        finally:
            server.terminate()
            server.wait(timeout=5)
