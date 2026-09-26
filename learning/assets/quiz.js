/* 教学区共享测验组件：mountQuiz(containerId, questions)
   questions: [{ scenario, options: [{ text, correct, why }] }]
   行为：点选即判分 + 即时反馈；答完显示总分。零依赖。 */

function mountQuiz(containerId, questions) {
  const root = document.getElementById(containerId);
  if (!root) return;
  let answered = 0, correct = 0;

  questions.forEach((q, qi) => {
    const block = document.createElement("section");
    block.className = "quiz";

    const head = document.createElement("h3");
    head.textContent = `Q${qi + 1}`;
    block.appendChild(head);

    const scenario = document.createElement("div");
    scenario.className = "q-scenario";
    scenario.innerHTML = q.scenario; // 课程作者提供的受控 HTML（表格等）
    block.appendChild(scenario);

    const list = document.createElement("ul");
    list.className = "q-options";
    const feedback = document.createElement("div");
    feedback.className = "q-feedback";

    let done = false;
    q.options.forEach((opt) => {
      const li = document.createElement("li");
      const btn = document.createElement("button");
      btn.textContent = opt.text;
      btn.addEventListener("click", () => {
        if (done) return;
        done = true;
        answered += 1;
        if (opt.correct) {
          correct += 1;
          btn.classList.add("correct");
          feedback.classList.add("good");
        } else {
          btn.classList.add("wrong");
          feedback.classList.add("bad");
          // 同时点亮正确项，形成对照
          list.querySelectorAll("button").forEach((b, bi) => {
            if (q.options[bi].correct) b.classList.add("correct");
          });
        }
        feedback.innerHTML = opt.why;
        feedback.classList.add("show");
        list.querySelectorAll("button").forEach((b) => (b.disabled = true));
        if (answered === questions.length) {
          const score = root.querySelector(".quiz-score");
          score.textContent = `完成：${correct} / ${questions.length}。` +
            (correct === questions.length
              ? "全对——回 working 记忆前，隔几天再回来重测一遍（间隔效应）。"
              : "错过的题读一遍反馈里的规则出处，隔天重测本页（检索练习比重读有效）。");
        }
      });
      li.appendChild(btn);
      list.appendChild(li);
    });

    block.appendChild(list);
    block.appendChild(feedback);
    root.appendChild(block);
  });

  const score = document.createElement("div");
  score.className = "quiz-score";
  score.textContent = "完成全部题目后，这里显示得分。";
  root.appendChild(score);
}
