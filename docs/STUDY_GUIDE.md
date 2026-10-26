# Read this project from the beginning

You do not need to understand everything at once. A socket is one endpoint of a TCP conversation. A thread is one independently scheduled path through the program. A queue holds work until a thread is available. These ideas are introduced one at a time.

List the lessons in their original order:

```sh
git log --reverse --oneline
```

Read a commit without changing your files:

```sh
git show <commit>
```

To run an earlier lesson without disturbing the main checkout:

```sh
git worktree add --detach ../astra-lesson <commit>
cd ../astra-lesson
./mvnw clean verify
```

Every implementation lesson adds a file under `docs/lessons/` explaining what changed, which classes to read, an experiment to try, and a question to answer. Follow those notes before reading the entire codebase.
