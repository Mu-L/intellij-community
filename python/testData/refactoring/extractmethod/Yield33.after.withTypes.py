def f(xs):
    found = False
    found = yield from bar(found, xs)
    print(found)


def bar(found_new: bool, xs_new) -> bool:
    for x in xs_new:
        yield x
        found_new = True
    return found_new