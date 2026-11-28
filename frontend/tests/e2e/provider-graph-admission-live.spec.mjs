import {randomUUID} from 'node:crypto';
import {expect, test} from '@playwright/test';
test.skip(
  process.env.PROVIDER_GRAPH_ADMISSION_VAULT_LIVE !== 'true',
  'Requires isolated native PostgreSQL, TLS Vault and controlled worker.',
);
test('accepted diamond graph admits all Runs and releases dependencies after exact ACCEPT using real backend', async ({
  page,
}) => {
  test.setTimeout(110000);
  const controlStatuses = [];
  page.on('close', () =>
    console.log(
      'PROVIDER_GRAPH_CONTROL_DIAGNOSTIC statuses=' + controlStatuses.join(','),
    ),
  );
  page.on('response', response => {
    if (
      response.request().method() === 'POST' &&
      new URL(response.url()).pathname.endsWith('/execution-controls')
    )
      controlStatuses.push(response.status());
  });

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
    if(name==='Graph A'){
      const startPath=new URL(page.url()).pathname;
      const id=startPath.match(/execution-runs\/([0-9a-f-]{36})\/start$/)?.[1];
      expect(id).toBeTruthy();
      const startApi=`/api/v1/organizations/${org}/execution-runs/${id}/start`;
      const oldPreviewResponse=await page.request.get(startApi);
      expect(oldPreviewResponse.status()).toBe(200);
      const oldPreview=(await oldPreviewResponse.json()).data;
      expect(oldPreview.toolApprovalFingerprint).toBeNull();

      await page.goto(`/workspace/${org}/execution-runs/${id}/text-tool-approval`);
      await expect(page.getByLabel('Nội dung Tool')).toBeVisible();
      await expect(page.getByRole('button',{name:'Duyệt Tool',exact:true})).toBeDisabled();
      const approvalUrl=`**/api/v1/organizations/${org}/execution-runs/${id}/text-tool-approval`;
      let committedReplyDropped=false,approvalPosts=0;
      let approvedFingerprint;
      await page.route(approvalUrl,async route=>{
        if(route.request().method()!=='POST')return route.continue();
        approvalPosts++;
        // Real backend commits before returning. Discard only this successful reply, never fabricate it.
        const response=await route.fetch({maxRetries:0});
        expect(response.status()).toBe(200);
        const result=await response.json();
        approvedFingerprint=result.data.fingerprint;
        expect(approvedFingerprint).toMatch(/^[0-9a-f]{64}$/);
        committedReplyDropped=true;
        await route.abort('failed');
      });
      await page.getByRole('checkbox').check();
      await page.getByRole('button',{name:'Duyệt Tool',exact:true}).click();
      await expect(page.getByRole('button',{name:'Kiểm tra biên nhận đã gửi',exact:true})).toBeEnabled();
      expect(committedReplyDropped).toBe(true);
      const receiptResponse=page.waitForResponse(r=>r.request().method()==='GET'&&new URL(r.url()).pathname.includes(`/execution-runs/${id}/text-tool-approval/receipts/`));
      await page.getByRole('button',{name:'Kiểm tra biên nhận đã gửi',exact:true}).click();
      const receipt=await receiptResponse;
      expect(receipt.status()).toBe(200);
      expect((await receipt.json()).data.fingerprint).toBe(approvedFingerprint);
      await expect(page.getByText('Đã khôi phục biên nhận lịch sử.',{exact:true})).toBeVisible();
      await expect(page.getByRole('heading',{name:'Nội dung đã duyệt trong biên nhận',exact:true})).toBeVisible();
      await expect(page.getByRole('link',{name:'Xem trước và Start Run',exact:true})).toHaveCount(0);
      expect(approvalPosts).toBe(1);
      const freshPreviewResponse=await page.request.get(startApi);
      expect(freshPreviewResponse.status()).toBe(200);
      const freshPreview=(await freshPreviewResponse.json()).data;
      expect(freshPreview.toolApprovalFingerprint).toBe(approvedFingerprint);
      expect(freshPreview.fingerprint).not.toBe(oldPreview.fingerprint);
      expect(freshPreview.revision).toBe(oldPreview.revision);
      expect(freshPreview.requestPreview).toBe(oldPreview.requestPreview);
      const csrfResponse=await page.request.get('/api/v1/auth/csrf');
      expect(csrfResponse.status()).toBe(200);
      const csrf=(await csrfResponse.json()).data;
      expect(csrf.headerName).toBe('X-CSRF-TOKEN');
      const staleStart=await page.request.post(startApi,{headers:{[csrf.headerName]:csrf.token},data:{expectedRevision:oldPreview.revision,idempotencyKey:randomUUID(),fingerprint:oldPreview.fingerprint}});
      expect(staleStart.status()).toBe(409);
      const untouchedRunResponse=await page.request.get(`/api/v1/organizations/${org}/execution-runs/${id}`);
      expect(untouchedRunResponse.status()).toBe(200);
      const untouchedRun=(await untouchedRunResponse.json()).data;
      expect(untouchedRun.status).toBe('CREATED');
      expect(untouchedRun.revision).toBe(oldPreview.revision);
      expect(untouchedRun.dispatches).toEqual([]);
      expect(untouchedRun.attempts).toEqual([]);
      expect(untouchedRun.toolEffects).toEqual([]);

      await page.unroute(approvalUrl);
      await page.getByRole('link',{name:'Về chi tiết Run',exact:true}).click();
      await page.getByRole('link',{name:'Khởi chạy Task',exact:true}).click();
      await expect(page.getByLabel('Request đề xuất')).toContainText(name);
      await expect(page.getByRole('heading',{name:'Tool đã chọn trước model',exact:true})).toBeVisible();
    }

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
    const runLink = page.getByRole('link', {name:'Về lần chạy / kiểm tra trạng thái',exact:true});
    const runPath = await runLink.getAttribute('href');
    expect(runPath).toMatch(new RegExp('^/workspace/'+org+'/execution-runs/[0-9a-f-]{36}$'));
    await runLink.click();
    await expect(page).toHaveURL(new RegExp('/execution-runs/[0-9a-f-]{36}$'));
    if(name==='Graph A'){
      const recoveryLink=page.getByRole('link',{name:'Kiểm tra khả năng chuẩn bị lại Run',exact:true});
      await expect.poll(async()=>{
        if(await recoveryLink.isVisible())return true;
        const refresh=page.getByRole('button',{name:'Tải lại',exact:true});
        if(await refresh.isEnabled().catch(()=>false))await refresh.click();
        return recoveryLink.isVisible();
      },{timeout:20000}).toBe(true);
      await recoveryLink.click();
      await expect(page.getByRole('button',{name:'Xác nhận chuẩn bị lại',exact:true})).toBeDisabled();
      const recoveryPath=new URL(page.url()).pathname;
      const recoveryRun=recoveryPath.match(/execution-runs\/([0-9a-f-]{36})\/unsent-recovery$/)?.[1];
      expect(recoveryRun).toBeTruthy();
      const recoveryApi=`/api/v1/organizations/${org}/execution-runs/${recoveryRun}/unsent-recovery`;
      const recoveryCommands=[];
      let startsDuringRecovery=0;
      const countStarts=request=>{
        if(request.method()==='POST'&&new URL(request.url()).pathname.endsWith('/start'))startsDuringRecovery++;
      };
      page.on('request',countStarts);
      await page.route('**'+recoveryApi,async route=>{
        if(route.request().method()!=='POST')return route.continue();
        recoveryCommands.push(route.request().postDataJSON());
        // Commit in the real backend first; only its successful reply is discarded.
        const response=await route.fetch({maxRetries:0});
        expect(response.status()).toBe(200);
        if(recoveryCommands.length===1)return route.abort('failed');
        return route.fulfill({response});
      });
      await page.getByRole('checkbox').check();
      await page.getByRole('button',{name:'Xác nhận chuẩn bị lại',exact:true}).click();
      await expect(page.getByText('Lệnh chưa xác định kết quả. Giữ nguyên mã lệnh; không tạo lệnh mới.',{exact:true})).toBeVisible();
      await expect(page.getByRole('heading',{name:'Biên nhận chuẩn bị lại',exact:true})).toHaveCount(0);
      // Pending is rendered before transport completes; wait for the dropped reply to settle.
      await expect(page.getByRole('button',{name:'Gửi lại đúng lệnh chuẩn bị',exact:true})).toBeEnabled();
      const prepared=await page.request.get(`/api/v1/organizations/${org}/execution-runs/${recoveryRun}`);
      expect(prepared.status()).toBe(200);
      const preparedRun=(await prepared.json()).data;
      expect(preparedRun.status).toBe('CREATED');
      expect(preparedRun.revision).toBe('3');
      const storedReceipt=await page.request.get(`${recoveryApi}/receipts/${recoveryCommands[0].idempotencyKey}`);
      expect(storedReceipt.status()).toBe(200);
      expect(storedReceipt.headers()['cache-control']).toBe('no-store');
      expect((await storedReceipt.json()).data).toEqual({runId:recoveryRun,idempotencyKey:recoveryCommands[0].idempotencyKey,sourceRevision:'2',resultingRevision:'3'});
      expect(recoveryCommands).toHaveLength(1);
      expect(startsDuringRecovery).toBe(0);
      const lookupInput=page.getByLabel('Mã lệnh chuẩn bị',{exact:true});
      const lookupButton=page.getByRole('button',{name:'Tra cứu biên nhận',exact:true});
      await lookupInput.fill(randomUUID());
      await lookupButton.click();
      await expect(page.getByText('Không tìm thấy biên nhận trong phạm vi hiện tại. Kết quả lệnh chưa được xác nhận.',{exact:true})).toBeVisible();
      await expect(page.getByRole('heading',{name:'Biên nhận lịch sử',exact:true})).toHaveCount(0);
      await expect(page.getByRole('checkbox')).toHaveCount(0);
      await lookupInput.fill(recoveryCommands[0].idempotencyKey);
      await lookupButton.click();
      await expect(page.getByRole('heading',{name:'Biên nhận lịch sử',exact:true})).toBeVisible();
      await expect(page.getByText('Phiên bản 2 → 3',{exact:true})).toBeVisible();
      await expect(page.getByText('Biên nhận không xác nhận trạng thái Run hiện tại và không cấp quyền Start.',{exact:true})).toBeVisible();
      expect(recoveryCommands).toHaveLength(1);
      expect(startsDuringRecovery).toBe(0);
      await expect(page.getByRole('button',{name:'Gửi lại đúng lệnh chuẩn bị',exact:true})).toBeEnabled();
      await page.getByRole('button',{name:'Kiểm tra điều kiện hiện tại',exact:true}).click();
      await expect(page.getByRole('checkbox')).toHaveCount(0);
      await page.getByRole('button',{name:'Gửi lại đúng lệnh chuẩn bị',exact:true}).click();
      await expect(page.getByRole('heading',{name:'Biên nhận chuẩn bị lại',exact:true})).toBeVisible();
      expect(recoveryCommands).toHaveLength(2);
      expect(recoveryCommands[1]).toEqual(recoveryCommands[0]);
      expect(startsDuringRecovery).toBe(0);
      page.off('request',countStarts);
      await page.unroute('**'+recoveryApi);
      await expect(page.getByText('Phiên bản nguồn 2 → 3',{exact:true})).toBeVisible();
      await page.getByRole('link',{name:'Về chi tiết Run',exact:true}).click();
      await page.getByRole('link',{name:'Duyệt text.metrics trước khi Start',exact:true}).click();
      await page.getByRole('checkbox').check();
      await page.getByRole('button',{name:'Duyệt Tool',exact:true}).click();
      await page.getByRole('link',{name:'Xem trước và Start Run',exact:true}).click();
      await expect(page.getByRole('heading',{name:'Tool đã chọn trước model',exact:true})).toBeVisible();
      await page.getByRole('button',{name:'Xác nhận Start Task',exact:true}).click();
      await expect(page.getByRole('status')).toContainText('Đã ghi nhận Start.');
      await page.getByRole('link',{name:'Về lần chạy / kiểm tra trạng thái',exact:true}).click();
    }
    if (name === 'Graph A') {
      await page.goto(
        `/workspace/${org}/teams/${team}/missions/${mission}/execution-controls`,
      );
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
              .getByText('Request đã gửi đang chờ kết quả: 1', {exact: true})
              .isVisible();
          },
          {timeout: 15000},
        )
        .toBe(true);
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
    if(name==='Graph A'){
      await expect(page.getByRole('heading',{name:'100 kết quả Tool gần nhất',exact:true})).toBeVisible();
      await expect(page.getByText(/text.metrics v1/)).toBeVisible();
      await expect(page.getByText(/Chi phí đã đối soát: 0 USD/)).toBeVisible();
    }
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
  const editor=page.getByLabel('JSON đề xuất',{exact:true});
  const restore=page.getByRole('button',{name:'Khôi phục JSON nguồn đã ACCEPT',exact:true});
  await expect(restore).toBeDisabled();
  await editor.fill('{bad');
  await expect(page.getByRole('alert')).toContainText('JSON chưa hợp lệ.');
  await expect(page.getByRole('button',{name:'Xem trước toàn bộ kế hoạch',exact:true})).toBeDisabled();
  const corrected=process.env.PROVIDER_PLAN_PROPOSAL_JSON+' ';
  await editor.fill(corrected);
  for(const key of ['a','b','c','d'])await page.getByLabel('Agent cho '+key,{exact:true}).selectOption(process.env.PROVIDER_PLAN_PROPOSAL_OWNER);
  await page.getByRole('button',{name:'Xem trước toàn bộ kế hoạch',exact:true}).click();
  await expect(page.getByText(/HUMAN_CORRECTED · Output/)).toBeVisible();
  page.once('dialog',dialog=>dialog.dismiss());
  await restore.click();
  await expect(editor).toHaveValue(corrected);
  await expect(page.getByRole('heading',{name:'Kế hoạch sẽ nhập',exact:true})).toBeVisible();
  page.once('dialog',dialog=>dialog.accept());
  await restore.click();
  await expect(editor).toHaveValue(process.env.PROVIDER_PLAN_PROPOSAL_JSON);
  await expect(page.getByRole('heading',{name:'Kế hoạch sẽ nhập',exact:true})).toHaveCount(0);
  await expect(page.getByRole('button',{name:'Áp dụng thành Task DRAFT',exact:true})).toHaveCount(0);
  for(const key of ['a','b','c','d'])await expect(page.getByLabel('Agent cho '+key,{exact:true})).toHaveValue('');
  await expect(page.getByRole('button',{name:'Xem trước toàn bộ kế hoạch',exact:true})).toBeDisabled();
  let bulkPreviewPosts=0,bulkApplyPosts=0;
  const countBulkCommands=request=>{
    if(request.method()!=='POST')return;
    const pathname=new URL(request.url()).pathname;
    if(pathname.endsWith('/plan-proposal/preview'))bulkPreviewPosts++;
    if(pathname.endsWith('/plan-proposal/apply'))bulkApplyPosts++;
  };
  page.on('request',countBulkCommands);
  const bulk=page.getByRole('button',{name:'Gán Agent đã chọn cho toàn bộ Task',exact:true});
  await expect(bulk).toBeDisabled();
  await page.getByLabel('Agent dùng cho toàn bộ Task',{exact:true}).selectOption(process.env.PROVIDER_PLAN_PROPOSAL_OWNER);
  for(const key of ['a','b','c','d'])await expect(page.getByLabel('Agent cho '+key,{exact:true})).toHaveValue('');
  await bulk.click();
  for(const key of ['a','b','c','d'])await expect(page.getByLabel('Agent cho '+key,{exact:true})).toHaveValue(process.env.PROVIDER_PLAN_PROPOSAL_OWNER);
  expect(bulkPreviewPosts).toBe(0);
  expect(bulkApplyPosts).toBe(0);
  await page
    .getByRole('button', {name: 'Xem trước toàn bộ kế hoạch', exact: true})
    .click();
  await expect(page.locator('article')).toHaveCount(4);
  await expect(page.getByText(/AGENT_OUTPUT · Output/)).toBeVisible();
  expect(bulkPreviewPosts).toBe(1);
  expect(bulkApplyPosts).toBe(0);
  await bulk.click();
  await expect(page.getByRole('heading',{name:'Kế hoạch sẽ nhập',exact:true})).toHaveCount(0);
  await expect(page.getByRole('button',{name:'Áp dụng thành Task DRAFT',exact:true})).toHaveCount(0);
  expect(bulkPreviewPosts).toBe(1);
  expect(bulkApplyPosts).toBe(0);
  await page.getByRole('button',{name:'Xem trước toàn bộ kế hoạch',exact:true}).click();
  await expect(page.getByText(/AGENT_OUTPUT · Output/)).toBeVisible();
  expect(bulkPreviewPosts).toBe(2);

  const applyApi=`**/api/v1/organizations/${org}/teams/${team}/missions/${process.env.PROVIDER_PLAN_PROPOSAL_TARGET}/plan-proposal/apply`;
  const applyCommands=[];
  await page.route(applyApi,async route=>{
    if(route.request().method()!=='POST')return route.continue();
    applyCommands.push(route.request().postDataJSON());
    // Fault-only409 is explicit: it is not a real backend conflict or a successful receipt.
    if(applyCommands.length===2)return route.fulfill({status:409,json:{error:'Injected retry conflict'}});
    const response=await route.fetch({maxRetries:0});
    expect(response.status()).toBe(200);
    expect(response.headers()['idempotency-replayed']).toBe(applyCommands.length===1?'false':'true');
    if(applyCommands.length===1)return route.abort('failed');
    return route.fulfill({response});
  });
  await page
    .getByRole('button', {name: 'Áp dụng thành Task DRAFT', exact: true})
    .click();
  await expect(page.getByRole('status')).toHaveText('Chưa xác định kết quả. Gửi lại đúng lệnh đang giữ để đối chiếu.');
  const applyRetry=page.getByRole('button',{name:'Gửi lại đúng lệnh áp dụng',exact:true});
  await expect(applyRetry).toBeEnabled();
  expect(applyCommands).toHaveLength(1);
  await expect(page.getByRole('heading',{name:'Đã nhập 4 Task',exact:true})).toHaveCount(0);
  page.once('dialog',async dialog=>{
    expect(dialog.message()).toContain('chưa rõ kết quả');
    await dialog.dismiss();
  });
  await page.getByRole('link',{name:'Về Mission workspace',exact:true}).click();
  await expect(page).toHaveURL(new RegExp('/plan-proposal$'));
  await applyRetry.click();
  await expect.poll(()=>applyCommands.length).toBe(2);
  await expect(applyRetry).toBeEnabled();
  await expect(page.getByRole('button',{name:'Tải lại điều kiện nhập',exact:true})).toBeDisabled();
  expect(applyCommands[1]).toEqual(applyCommands[0]);
  await applyRetry.click();

  await expect(
    page.getByRole('heading', {name: 'Đã nhập 4 Task', exact: true}),
  ).toBeVisible();
  expect(bulkApplyPosts).toBe(3);
  expect(applyCommands).toHaveLength(3);
  expect(applyCommands[2]).toEqual(applyCommands[0]);
  await page.unroute(applyApi);
  page.off('request',countBulkCommands);
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
