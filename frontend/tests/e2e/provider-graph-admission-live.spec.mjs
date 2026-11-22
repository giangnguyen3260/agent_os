import {expect, test} from '@playwright/test';
test.skip(
  process.env.PROVIDER_GRAPH_ADMISSION_VAULT_LIVE !== 'true',
  'Requires isolated native PostgreSQL, TLS Vault and controlled worker.',
);
test('accepted diamond graph admits all Runs and releases dependencies after exact ACCEPT using real backend', async ({
  page,
}) => {
  test.setTimeout(110000);
  const org = process.env.PROVIDER_VAULT_ORG,
    team = process.env.PROVIDER_OPENAI_EXECUTION_TEAM,
    mission = process.env.PROVIDER_OPENAI_EXECUTION_MISSION;
  if (!org || !team || !mission) throw new Error('Missing graph fixture');
  const path = `/workspace/${org}/teams/${team}/missions/${mission}/plan-review`;
  await page.goto('/login');
  await page
    .getByLabel('Email', {exact: true})
    .fill(process.env.PROVIDER_VAULT_EMAIL);
  await page
    .getByLabel('Mật khẩu', {exact: true})
    .fill(process.env.PROVIDER_VAULT_PASSWORD);
  await page.getByRole('button', {name: 'Đăng nhập', exact: true}).click();
  await expect(page).toHaveURL(/\/workspace$/);
  await page.goto(path);
  await page
    .getByLabel(
      'Tôi xác nhận toàn bộ Task, nội dung và quan hệ phụ thuộc hiển thị hiện tại.',
    )
    .check();
  await page
    .getByRole('button', {name: 'Chấp nhận kế hoạch', exact: true})
    .click();
  await expect(
    page.getByText('Khớp cấu hình hiện tại.', {exact: true}),
  ).toBeVisible();
  await page
    .getByLabel('Tôi xác nhận tiếp nhận toàn bộ kế hoạch đã duyệt này.')
    .check();
  await page
    .getByRole('button', {name: 'Tiếp nhận toàn bộ kế hoạch', exact: true})
    .click();
  const panel = () =>
    page.locator('section').filter({
      has: page.getByRole('heading', {
        name: 'Thực thi theo kế hoạch',
        exact: true,
      }),
    });
  const task = name =>
    panel()
      .locator('article')
      .filter({has: page.getByRole('heading', {name, exact: true})});
  await expect(panel().locator('article')).toHaveCount(4);
  await expect(task('Graph B')).toContainText('BLOCKED');
  await expect(task('Graph C')).toContainText('BLOCKED');
  await expect(task('Graph D')).toContainText('BLOCKED');
  let plannerRun;
  for (const name of ['Graph A', 'Graph B', 'Graph C', 'Graph D']) {
    await expect(
      task(name).getByRole('link', {
        name: 'Xem trước và Start Run',
        exact: true,
      }),
    ).toBeVisible();
    await task(name)
      .getByRole('link', {name: 'Xem trước và Start Run', exact: true})
      .click();
    await expect(page.getByLabel('Request đề xuất')).toContainText(name);
    if (name === 'Graph B' || name === 'Graph C') {
      await expect(page.getByLabel('Request đề xuất')).toContainText(
        'Graph A verified task output',
      );
    }
    if (name === 'Graph D') {
      await expect(page.getByLabel('Request đề xuất')).toContainText(
        'Graph B verified task output',
      );
      await expect(page.getByLabel('Request đề xuất')).toContainText(
        'Graph C verified task output',
      );
      await expect(page.getByLabel('Request đề xuất')).not.toContainText(
        'Graph A verified task output',
      );
    }

    await page
      .getByRole('button', {name: 'Xác nhận Start Task', exact: true})
      .click();
    await expect(page.getByRole('status')).toContainText('Đã ghi nhận Start.');
    await page
      .getByRole('link', {
        name: 'Về lần chạy / kiểm tra trạng thái',
        exact: true,
      })
      .click();
    if (name === 'Graph A') {
      const runPath = new URL(page.url()).pathname;
      await page.goto(
        `/workspace/${org}/teams/${team}/missions/${mission}/execution-controls`,
      );
      await expect(
        page.getByRole('button', {name: 'Tạm dừng thực thi', exact: true}),
      ).toBeEnabled();
      await page
        .getByRole('button', {name: 'Tạm dừng thực thi', exact: true})
        .click();
      await page
        .getByLabel(
          'Tôi xác nhận trạng thái hiện tại và tác động của lệnh này.',
        )
        .check();
      await page
        .getByRole('button', {name: 'Gửi lệnh đã xác nhận', exact: true})
        .click();
      await expect(
        page
          .getByRole('status')
          .filter({hasText: 'Đã ghi nhận Tạm dừng thực thi'}),
      ).toBeVisible();
      await expect
        .poll(
          async () => {
            const refresh = page.getByRole('button', {
              name: 'Kiểm tra trạng thái hiện tại',
              exact: true,
            });
            if (await refresh.isEnabled().catch(() => false))
              await refresh.click();
            return page
              .getByRole('button', {name: 'Tiếp tục thực thi', exact: true})
              .isVisible();
          },
          {timeout: 15000},
        )
        .toBe(true);
      await page
        .getByRole('button', {name: 'Tiếp tục thực thi', exact: true})
        .click();
      await page
        .getByLabel(
          'Tôi xác nhận trạng thái hiện tại và tác động của lệnh này.',
        )
        .check();
      await page
        .getByRole('button', {name: 'Gửi lệnh đã xác nhận', exact: true})
        .click();
      await expect(
        page
          .getByRole('status')
          .filter({hasText: 'Đã ghi nhận Tiếp tục thực thi'}),
      ).toBeVisible();
      await page.goto(runPath);
    }
    const review = page.getByRole('link', {
      name: 'Duyệt kết quả thực thi',
      exact: true,
    });
    await expect
      .poll(
        async () => {
          if (await review.isVisible()) return true;
          const refresh = page.getByRole('button', {
            name: 'Tải lại',
            exact: true,
          });
          if (await refresh.isEnabled().catch(() => false))
            await refresh.click();
          return review.isVisible();
        },
        {timeout: 40000},
      )
      .toBe(true);
    await review.click();
    if (name === 'Graph D')
      plannerRun = new URL(page.url()).pathname.match(
        /execution-runs\/([^/]+)/,
      )?.[1];
    await expect(page.getByLabel('Nội dung kết quả')).toHaveText(
      name === 'Graph D'
        ? process.env.PROVIDER_PLAN_PROPOSAL_JSON
        : name + ' verified task output',
    );
    await page
      .getByRole('button', {name: 'Duyệt kết quả', exact: true})
      .click();
    await page.getByRole('button', {name: 'Xác nhận', exact: true}).click();
    await expect(page.getByRole('status')).toContainText('Đã duyệt');
    await page.goto(path);
    await expect(task(name)).toContainText('COMPLETED');
    if (name === 'Graph B')
      await expect(task('Graph D')).toContainText('BLOCKED');
  }
  await expect(panel()).toContainText('RUNNING');
  await panel()
    .getByRole('link', {name: 'Hoàn tất Mission theo kế hoạch', exact: true})
    .click();
  await expect(
    page.getByRole('heading', {
      name: 'Trạng thái hiện tại: RUNNING',
      exact: true,
    }),
  ).toBeVisible();
  await expect(page.locator('article')).toHaveCount(4);
  await page
    .getByRole('button', {name: 'Xem output đã ACCEPT', exact: true})
    .first()
    .click();
  await expect(page.getByLabel('Nội dung output đã ACCEPT')).toHaveText(
    'Graph A verified task output',
  );
  for (const [button, status] of [
    ['Đưa Mission vào review', 'REVIEW'],
    ['Hoàn tất Mission', 'COMPLETED'],
  ]) {
    await page
      .getByLabel(
        'Tôi xác nhận toàn bộ bản kê đầu ra và bước chuyển trạng thái này.',
      )
      .check();
    await page.getByRole('button', {name: button, exact: true}).click();
    await expect(
      page.getByRole('heading', {
        name: `Trạng thái hiện tại: ${status}`,
        exact: true,
      }),
    ).toBeVisible();
  }
  await page.reload();
  await expect(
    page.getByRole('heading', {
      name: 'Trạng thái hiện tại: COMPLETED',
      exact: true,
    }),
  ).toBeVisible();
  await page
    .getByRole('button', {name: 'Xem output đã ACCEPT', exact: true})
    .first()
    .click();
  await expect(page.getByLabel('Nội dung output đã ACCEPT')).toHaveText(
    'Graph A verified task output',
  );
  if (
    !plannerRun ||
    !process.env.PROVIDER_PLAN_PROPOSAL_TARGET ||
    !process.env.PROVIDER_PLAN_PROPOSAL_OWNER
  )
    throw new Error('Missing reviewed plan fixture');
  const proposalPath = `/workspace/${org}/teams/${team}/missions/${process.env.PROVIDER_PLAN_PROPOSAL_TARGET}/plan-proposal`;
  await page.goto(proposalPath);
  await expect(
    page.getByRole('heading', {name: 'Imported Mission', exact: true}),
  ).toBeVisible();
  await page
    .getByLabel('Run lập kế hoạch nguồn', {exact: true})
    .fill(plannerRun);
  await page
    .getByRole('button', {name: 'Đọc output nguồn đã ACCEPT', exact: true})
    .click();
  await expect(page.getByLabel('JSON đề xuất', {exact: true})).toHaveValue(
    process.env.PROVIDER_PLAN_PROPOSAL_JSON,
  );
  for (const key of ['a', 'b', 'c', 'd'])
    await page
      .getByLabel('Agent cho ' + key, {exact: true})
      .selectOption(process.env.PROVIDER_PLAN_PROPOSAL_OWNER);
  await page
    .getByRole('button', {name: 'Xem trước toàn bộ kế hoạch', exact: true})
    .click();
  await expect(page.locator('article')).toHaveCount(4);
  await expect(page.getByText(/AGENT_OUTPUT · Output/)).toBeVisible();
  await page
    .getByRole('button', {name: 'Áp dụng thành Task DRAFT', exact: true})
    .click();
  await expect(
    page.getByRole('heading', {name: 'Đã nhập 4 Task', exact: true}),
  ).toBeVisible();
  await page
    .getByRole('link', {name: 'Xem và duyệt kế hoạch từ Task', exact: true})
    .click();
  await expect(
    page.getByRole('heading', {
      name: 'Kế hoạch từ Task đã cấu hình',
      exact: true,
    }),
  ).toBeVisible();
  await expect(page.locator('article')).toHaveCount(4);
});
