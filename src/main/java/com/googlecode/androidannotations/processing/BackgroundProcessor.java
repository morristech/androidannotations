/*
 * Copyright 2010-2011 Pierre-Yves Ricau (py.ricau at gmail.com)
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.googlecode.androidannotations.processing;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.VariableElement;

import com.googlecode.androidannotations.annotations.Background;
import com.googlecode.androidannotations.helper.AndroidManifest;
import com.sun.codemodel.JBlock;
import com.sun.codemodel.JCatchBlock;
import com.sun.codemodel.JClass;
import com.sun.codemodel.JClassAlreadyExistsException;
import com.sun.codemodel.JCodeModel;
import com.sun.codemodel.JDefinedClass;
import com.sun.codemodel.JExpr;
import com.sun.codemodel.JExpression;
import com.sun.codemodel.JFieldVar;
import com.sun.codemodel.JInvocation;
import com.sun.codemodel.JMethod;
import com.sun.codemodel.JMod;
import com.sun.codemodel.JTryBlock;
import com.sun.codemodel.JVar;

public class BackgroundProcessor implements ElementProcessor {

	private final AndroidManifest androidManifest;

	public BackgroundProcessor(AndroidManifest androidManifest) {
		this.androidManifest = androidManifest;
	}

	@Override
	public Class<? extends Annotation> getTarget() {
		return Background.class;
	}

	@Override
	public void process(Element element, JCodeModel codeModel, ActivitiesHolder activitiesHolder) throws JClassAlreadyExistsException {

		ActivityHolder holder = activitiesHolder.getEnclosingActivityHolder(element);

		if (activitiesHolder.backgroundExecutor == null) {
			activitiesHolder.backgroundExecutor = codeModel._class(androidManifest.getApplicationPackage() + ".BackgroundExecutor_");

			JInvocation newCachedThreadPool = codeModel.ref(Executors.class).staticInvoke("newCachedThreadPool");

			JFieldVar executorStaticField = activitiesHolder.backgroundExecutor.field(JMod.STATIC | JMod.PRIVATE | JMod.FINAL, codeModel.ref(Executor.class), "executor", newCachedThreadPool);
			activitiesHolder.executorMethod = activitiesHolder.backgroundExecutor.method(JMod.PUBLIC | JMod.STATIC, codeModel.VOID, "execute");
			JVar runnableParam = activitiesHolder.executorMethod.param(Runnable.class, "runnable");
			activitiesHolder.executorMethod.body().invoke(executorStaticField, "execute").arg(runnableParam);
		}

		// Method
		String backgroundMethodName = element.getSimpleName().toString();
		JMethod backgroundMethod = holder.activity.method(JMod.PUBLIC, codeModel.VOID, backgroundMethodName);
		backgroundMethod.annotate(Override.class);

		// Method parameters
		List<JVar> parameters = new ArrayList<JVar>();
		ExecutableElement executableElement = (ExecutableElement) element;
		for (VariableElement parameter : executableElement.getParameters()) {
			String parameterName = parameter.getSimpleName().toString();
			JClass parameterClass = holder.refClass(parameter.asType().toString());
			JVar param = backgroundMethod.param(JMod.FINAL, parameterClass, parameterName);
			parameters.add(param);
		}

		JDefinedClass anonymousRunnableClass = codeModel.anonymousClass(Runnable.class);

		JMethod runMethod = anonymousRunnableClass.method(JMod.PUBLIC, codeModel.VOID, "run");
		runMethod.annotate(Override.class);

		JBlock runMethodBody = runMethod.body();
		JTryBlock runTry = runMethodBody._try();

		JExpression activitySuper = holder.activity.staticRef("super");

		JInvocation superCall = runTry.body().invoke(activitySuper, backgroundMethod);
		for (JVar param : parameters) {
			superCall.arg(param);
		}
		JCatchBlock runCatch = runTry._catch(holder.refClass(RuntimeException.class));
		JVar exceptionParam = runCatch.param("e");

		JClass logClass = holder.refClass("android.util.Log");

		JInvocation errorInvoke = logClass.staticInvoke("e");

		errorInvoke.arg(holder.activity.name());
		errorInvoke.arg("A runtime exception was thrown while executing code in a background thread");
		errorInvoke.arg(exceptionParam);

		runCatch.body().add(errorInvoke);

		JBlock backgroundBody = backgroundMethod.body();
		
		JInvocation executeCall = activitiesHolder.backgroundExecutor.staticInvoke(activitiesHolder.executorMethod).arg(JExpr._new(anonymousRunnableClass));
		
		backgroundBody.add(executeCall);
	}
}